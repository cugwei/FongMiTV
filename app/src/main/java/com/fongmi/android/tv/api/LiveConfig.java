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
        new Thread(() -> loadConfig(callback)).start();
    }

    private void loadConfig(Callback callback) {
        try {
            System.err.println(config.getUrl());
            parseConfig(Decoder.getJson(config.getUrl()), callback);
        } catch (Throwable e) {

            System.err.println("111" + config.getUrl());

            // 加载配置失败或者未配置时，使用内置配置再尝试一次（避免配置的服务地址失效）
            String backup_url = "https://coolapps.sinaapp.com/backup_config";
            if (TextUtils.isEmpty(config.getUrl()) || !config.getUrl().equals(backup_url)) {
                config.setUrl(backup_url);
                System.err.println("222" + config.getUrl());
                loadConfig(callback);
            }
            // if (TextUtils.isEmpty(config.getUrl())) App.post(() -> callback.error(""));
            else App.post(() -> callback.error(Notify.getError(R.string.error_config_get, e)));
            e.printStackTrace();
        }
    }

    private void parseConfig(String text, Callback callback) {
        System.err.println("6666" + config.getUrl());
        if (Json.invalid(text)) {
            System.err.println("7777" + config.getUrl());
            parseText(text, callback);
        } else {
            checkJson(JsonParser.parseString(text).getAsJsonObject(), callback);
        }
    }

    private void parseText(String text, Callback callback) {
        System.err.println("8888" + text);
        Live live = new Live(config.getUrl());
        LiveParser.text(live, text);
        App.post(callback::success);
        lives.remove(live);
        lives.add(live);
        setHome(live);
    }

    private void checkJson(JsonObject object, Callback callback) {
        System.err.println("333" + config.getUrl());
        if (object.has("urls")) {
            parseDepot(object, callback);
        } else {
            parseConfig(object, callback);
        }
    }

    public void parseDepot(JsonObject object, Callback callback) {
        System.err.println("444" + config.getUrl());
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        List<Config> configs = new ArrayList<>();
        for (Depot item : items) configs.add(Config.find(item, 1));
        Config.delete(config.getUrl());
        config = configs.get(0);

//        // 将下发的持久存储下来
//        Config.delete(config.getUrl());
//        config.insert();

        loadConfig(callback);
    }

    public void parseConfig(JsonObject object, Callback callback) {
        System.err.println("555" + config.getUrl());
        if (!object.has("lives")) return;
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
