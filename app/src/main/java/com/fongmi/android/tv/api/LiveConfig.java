package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.activity.LiveActivity;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public class LiveConfig {

    private List<Live> lives;
    private Config config;
    private boolean same;
    private Live home;

    private static class Loader {
        static volatile LiveConfig INSTANCE = new LiveConfig();
    }

    public static LiveConfig get() {
        return Loader.INSTANCE;
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static String getResp() {
        return get().getHome().getCore().getResp();
    }

    public static int getHomeIndex() {
        return get().getLives().indexOf(get().getHome());
    }

    public static boolean isOnly() {
        return get().getLives().size() == 1;
    }

    public static boolean isEmpty() {
        return get().getHome() == null;
    }

    public static boolean hasUrl() {
        return getUrl() != null && getUrl().length() > 0;
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public LiveConfig init() {
        this.home = null;
        this.config = Config.live();
        this.lives = new ArrayList<>();
        return this;
    }

    public LiveConfig config(Config config) {
        this.config = config;
        this.same = config.getUrl().equals(ApiConfig.getUrl());
        return this;
    }

    public LiveConfig clear() {
        this.home = null;
        this.lives.clear();
        return this;
    }

    public void load() {
        if (isEmpty()) load(new Callback());
    }

    public void load(Callback callback) {
        new Thread(() -> loadConfig(callback, 0, config.getUrl())).start();
    }

    private void loadConfig(Callback callback, int tryTimes, String entryUrl) {
        try {
            checkJson(JsonParser.parseString(Decoder.getJson(config.getUrl())).getAsJsonObject(), callback, tryTimes, entryUrl);
        } catch (Throwable e) {
//            String[] backup_urls = {"https://coolapps.sinaapp.com/000tconfig.json",
//                    "https://coolapps.sinaapp.com/111tconfig.json",
//                    "https://coolapps.sinaapp.com/222tconfig.json"};

            String[] backup_urls = {"https://coolapps.sinaapp.com/tconfig.json",
                    "http://119.23.76.111/tconfig.json",
                    "https://raw.gitmirror.com/cugwei/config/main/tconfig.json",
                    "https://otas.sinaapp.com/tconfig.json"};
            boolean shouldRetry = tryTimes < backup_urls.length;
            if (shouldRetry &&
                    (TextUtils.isEmpty(config.getUrl()) || !config.getUrl().equals(backup_urls[tryTimes]))) {

                // 加载配置失败或者未配置时，使用内置配置再尝试一次（避免配置的服务地址失效）
                config.setUrl(backup_urls[tryTimes]);
                loadConfig(callback, tryTimes + 1, entryUrl);
            } else {

                config = Config.find(entryUrl, 1);
                config.update();

                App.post(() -> callback.error(Notify.getError(R.string.error_config_get, e)));
            }
            e.printStackTrace();
        }
    }

    /*
    增加内置备用url之后，不再支持下发txt配置的情况，所以以下方法废弃

    private void parseConfig(String text, Callback callback) {
        if (Json.invalid(text)) {
            parseText(text, callback);
        } else {
            checkJson(JsonParser.parseString(text).getAsJsonObject(), callback);
        }
    }

    private void parseText(String text, Callback callback) {
        Live live = new Live(config.getUrl());
        LiveParser.text(live, text);
        App.post(callback::success);
        lives.remove(live);
        lives.add(live);
        setHome(live);
    }
     */

    private void checkJson(JsonObject object, Callback callback, int tryTimes, String entryUrl) {
        if (object.has("urls")) {
            parseDepot(object, callback, tryTimes, entryUrl);
        } else {
            parseConfig(object, callback);
        }
    }

    public void parseDepot(JsonObject object, Callback callback, int tryTimes, String entryUrl) {
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());

        config = Config.find(items.get(0), 1);//越界之后交给上层catch

        // 使用下发的url替换当前url
        // 注意用原url中'/'之后的部分替换下发的url中的'{placeholder}'
        if (config.getUrl().endsWith("placeholder") && !TextUtils.isEmpty(entryUrl)) {
            String[] url_components = TextUtils.split(entryUrl, "/");
            if (url_components.length > 1) {
                String url = config.getUrl().replace("placeholder", url_components[url_components.length-1]);
                config.setUrl(url);
            }
        }

        loadConfig(callback, tryTimes, entryUrl);
    }

    public void parseConfig(JsonObject object, Callback callback) {
        if (!object.has("lives")) return;

        config = Config.find(config, 1);
        config.update();

        for (JsonElement element : Json.safeListElement(object, "lives")) add(Live.objectFrom(element).check());
        for (Live live : lives) if (live.getName().equals(config.getHome())) setHome(live);
        if (home == null) setHome(lives.isEmpty() ? new Live() : lives.get(0));
        if (home.isBoot() || Setting.isBootLive()) App.post(this::bootLive);
        if (callback != null) App.post(callback::success);
    }

    private void add(Live live) {
        if (!lives.contains(live)) lives.add(live);
    }

    private void bootLive() {
        Setting.putBootLive(false);
        LiveActivity.start(App.get());
    }

    public void parse(JsonObject object) {
        parseConfig(object, null);
    }

    private void setKeep(List<Group> items) {
        List<String> key = new ArrayList<>();
        for (Keep keep : Keep.getLive()) key.add(keep.getKey());
        for (Group group : items) {
            if (group.isKeep()) continue;
            for (Channel channel : group.getChannel()) {
                if (key.contains(channel.getName())) {
                    items.get(0).add(channel);
                }
            }
        }
    }

    private int[] getKeep(List<Group> items) {
        String[] splits = Setting.getKeep().split(AppDatabase.SYMBOL);
        if (!home.getName().equals(splits[0])) return new int[]{1, 0};
        for (int i = 0; i < items.size(); i++) {
            Group group = items.get(i);
            if (group.getName().equals(splits[1])) {
                int j = group.find(splits[2]);
                if (j != -1 && splits.length == 4) group.getChannel().get(j).setLine(splits[3]);
                if (j != -1) return new int[]{i, j};
            }
        }
        return new int[]{1, 0};
    }

    public void setKeep(Channel channel) {
        if (home == null || channel.getGroup().isHidden() || channel.getUrls().isEmpty()) return;
        Setting.putKeep(home.getName() + AppDatabase.SYMBOL + channel.getGroup().getName() + AppDatabase.SYMBOL + channel.getName() + AppDatabase.SYMBOL + channel.getCurrent());
    }

    public int[] find(List<Group> items) {
        setKeep(items);
        return getKeep(items);
    }

    public int[] find(String number, List<Group> items) {
        for (int i = 0; i < items.size(); i++) {
            int j = items.get(i).find(Integer.parseInt(number));
            if (j != -1) return new int[]{i, j};
        }
        return new int[]{-1, -1};
    }

    public boolean isSame(String url) {
        return same || TextUtils.isEmpty(config.getUrl()) || url.equals(config.getUrl());
    }

    public List<Live> getLives() {
        return lives;
    }

    public Config getConfig() {
        return config == null ? Config.live() : config;
    }

    public Live getHome() {
        return home;
    }

    public void setHome(Live home) {
        this.home = home;
        this.home.setActivated(true);
        config.home(home.getName()).update();
        for (Live item : lives) item.setActivated(home);
    }
}
