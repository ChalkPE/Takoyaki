/*
 * Copyright 2014-2015 ChalkPE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pe.chalk.takoyaki;

import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import pe.chalk.takoyaki.filter.ArticleFilter;
import pe.chalk.takoyaki.filter.CommentaryFilter;
import pe.chalk.takoyaki.filter.VisitationFilter;
import pe.chalk.takoyaki.model.Menu;
import pe.chalk.takoyaki.utils.Prefix;
import pe.chalk.takoyaki.logger.PrefixedLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author ChalkPE <chalkpe@gmail.com>
 * @since 2015-04-07
 */
public class Target extends Thread implements Prefix {
    private static final String STRING_CONTENT = "http://cafe.naver.com/%s.cafe";
    private static final String STRING_ARTICLE = "http://cafe.naver.com/ArticleList.nhn?search.clubid=%d&search.boardtype=L";
    private static final Pattern PATTERN_CLUB_ID = Pattern.compile("var g_sClubId = \"(\\d+)\";");

    private String contentUrl;
    private String articleUrl;

    private String prefix;
    private PrefixedLogger logger;

    private long interval;

    private Collector collector;
    private Staff staff;

    private final String address;
    private final int clubId;
    private List<Menu> menus;

    public Target(JSONObject properties){
        this.prefix = properties.getString("prefix");
        this.logger = new PrefixedLogger(this.getTakoyaki().getLogger(), this);
        this.staff = new Staff(this.logger, properties.getJSONObject("naverAccount"), properties.getInt("timeout"));

        this.interval = properties.getLong("interval");
        this.collector = new Collector(Takoyaki.<String>buildStream(properties.getJSONArray("filters")).map(filterName -> {
            switch(filterName){
                case ArticleFilter.NAME:
                    return new ArticleFilter(this);

                case CommentaryFilter.NAME:
                    return new CommentaryFilter(this);

                case VisitationFilter.NAME:
                    return new VisitationFilter(this);

                default:
                    return null;
            }
        }).filter(filter -> filter != null).collect(Collectors.toList()));

        this.address = properties.getString("address");
        this.contentUrl = String.format(STRING_CONTENT, this.getAddress());

        try{
            Document contentDocument = this.getStaff().parse(this.contentUrl);
            this.setName(contentDocument.select("h1.d-none").text());

            Matcher clubIdMatcher = Target.PATTERN_CLUB_ID.matcher(contentDocument.head().select("script:not([type]):not([src])").first().html());
            if(!clubIdMatcher.find()){
                throw new IllegalArgumentException("카페 ID를 찾을 수 없습니다: " + this.getName());
            }

            this.clubId = Integer.parseInt(clubIdMatcher.group(1));
            this.menus = contentDocument.select("a[id^=menuLink]").stream().map(element -> new Menu(this.getClubId(), Integer.parseInt(element.id().substring(8)), element.text())).collect(Collectors.toList());

            this.articleUrl = String.format(STRING_ARTICLE, this.getClubId());

            Files.write(Paths.get("Takoyaki-menus-" + this.getAddress() + ".log"), this.getMenus().stream().map(Menu::toString).collect(Collectors.toList()), StandardCharsets.UTF_8);
        }catch(IOException | JSONException e){
            String errorMessage = "모니터링이 불가합니다: " + e.getClass().getName() + ": " + e.getMessage();

            this.getLogger().error(errorMessage);
            throw new IllegalStateException(errorMessage);
        }
    }

    public Takoyaki getTakoyaki(){
        return Takoyaki.getInstance();
    }

    public PrefixedLogger getLogger(){
        return this.logger;
    }

    public Staff getStaff(){
        return this.staff;
    }

    public long getInterval(){
        return this.interval;
    }

    public String getAddress(){
        return this.address;
    }

    public int getClubId(){
        return this.clubId;
    }

    public List<Menu> getMenus(){
        return this.menus;
    }

    public Menu getMenu(int menuId){
        for(Menu menu : this.getMenus()){
            if(menu.getId() == menuId){
                return menu;
            }
        }
        return null;
    }

    @Override
    public String getPrefix(){
        return this.prefix;
    }

    @Override
    public void run(){
        this.getTakoyaki().getLogger().info("모니터링을 시작합니다: " + this);
        while(this.getTakoyaki().isAlive() && !this.isInterrupted()){
            try{
                Thread.sleep(this.getInterval());

                Document contentDocument = this.getStaff().parse(this.contentUrl);
                Document articleDocument = this.getStaff().parse(this.articleUrl);

                this.collector.collect(contentDocument, articleDocument);
            }catch(InterruptedException e){
                return;
            }catch(Exception e){
                this.getLogger().error(e.getClass().getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void interrupt(){
        super.interrupt();
        this.getStaff().close();
    }

    @Override
    public String toString(){
        return this.getClubId() + " (" + this.getName() + ")";
    }
}