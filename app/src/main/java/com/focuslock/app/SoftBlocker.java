package com.focuslock.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SoftBlocker extends AccessibilityService {
    private static final Set<String> CHROMIUM = new HashSet<>(Arrays.asList(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "com.brave.browser", "com.brave.browser_beta", "com.microsoft.emmx", "org.chromium.chrome"));
    private static final Set<String> FIREFOX = new HashSet<>(Arrays.asList(
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.fennec_fdroid"));
    private static final Set<String> LOCAL_PAGES = new HashSet<>(Arrays.asList(
            "about:blank", "about:newtab", "about:home", "chrome://newtab", "chrome://newtab/",
            "chrome-native://newtab", "chrome-native://newtab/", "edge://newtab", "edge://newtab/"));
    private static final int FIREFOX_SCAN_LIMIT = 96;
    private static final long IDLE_CHECK_MS = 1000;
    private static final long NAVIGATION_SETTLE_MS = 250;
    // The service and activity access this state on the main thread.
    private static SoftBlocker connected;
    private static Runnable connectionListener;

    private static final class Address {
        static final Address UNKNOWN = new Address("");
        static final Address EDITING = new Address("");
        static final Address LOCAL = new Address("");
        final String domain;

        Address(String domain) {
            this.domain = domain;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = this::checkWindow;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> {
        if ("end_elapsed".equals(key) || "end".equals(key) || "apps".equals(key) || "sites".equals(key)) reload();
    };
    private SoftStore store;
    private FocusHistory history;
    private SessionCompletion completion;
    private String sessionKey;
    private SoftStore.Session session;
    private SoftStore.Session test;
    private Set<String> detectedBrowsers;
    private Set<String> essentials;
    private Set<String> settingsApps;
    private KeyguardManager keyguard;
    private WindowManager windows;
    private BlockCover cover;
    private String customTabPackage = "";
    private int customTabWindow = -1;
    private int coveredWindowId = -1;
    private final BrowserNavigation navigation = new BrowserNavigation();
    private final CoverRelease release = new CoverRelease();
    private final AppReturn appReturn = new AppReturn();
    private long breakUntil;
    static void refreshBreak() {
        if (connected != null) connected.reload();
        reportChange();
    }

    private long navigationUntil;
    private boolean queued;

    static boolean enabled(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        ComponentName expected = new ComponentName(context, SoftBlocker.class);
        for (AccessibilityServiceInfo service :
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (expected.equals(ComponentName.unflattenFromString(service.getId()))) return true;
        }
        return false;
    }

    static boolean isConnected() {
        return connected != null;
    }

    static void observeConnection(Runnable listener) {
        connectionListener = listener;
    }

    static void unobserveConnection(Runnable listener) {
        if (connectionListener == listener) connectionListener = null;
    }

    static SoftStore.Session activeTest() {
        if (connected == null || connected.test == null) return null;
        return connected.test.active(SystemClock.elapsedRealtime()) ? connected.test : null;
    }

    static boolean startTest(Set<String> apps, Set<String> sites, boolean strict,
                             boolean allowlist, boolean allowBrowserTabs, boolean returnHome) {
        SoftBlocker service = connected;
        if (service == null || service.session.active(SystemClock.elapsedRealtime())) return false;
        service.test = new SoftStore.Session(new SessionClock(service).start(Rule.MINUTE),
                apps, sites, strict, allowlist, allowBrowserTabs, returnHome, true);
        service.reload();
        reportChange();
        return true;
    }

    static void endTest() {
        if (connected == null || connected.test == null) return;
        connected.test = null;
        connected.reload();
        reportChange();
    }

    private static void reportChange() {
        if (connectionListener != null) connectionListener.run();
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        keyguard = getSystemService(KeyguardManager.class);
        windows = getSystemService(WindowManager.class);
        if (store == null) {
            store = new SoftStore(this);
            history = new FocusHistory(this, true);
            completion = new SessionCompletion(this);
            store.observe(changed);
        }
        reload();
        connected = this;
        reportChange();
    }

    private void reload() {
        store.checkCompletion(new SessionClock(this), completion);
        if (test != null && !test.active(SystemClock.elapsedRealtime())) test = null;
        session = test == null ? store.read() : test;
        sessionKey = store.sessionId();
        essentials = AppCatalog.essentials(this);
        settingsApps = AppCatalog.settingsApps(getPackageManager());
        boolean inspectBrowsers = session.allowlist ? session.allowBrowserTabs : !session.sites.isEmpty();
        detectedBrowsers = inspectBrowsers ? AppCatalog.browsers(this) : java.util.Collections.emptySet();
        customTabWindow = -1;
        navigation.reset();
        breakUntil = EmergencyBreaks.current(this).until;
        LockNotifications.showSoft(this, session, breakUntil);
        handler.removeCallbacks(update);
        queued = false;
        release.reset();
        checkWindow();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (session == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!session.active(now) && now >= FocusService.settingsBlockedUntil) {
            hideCover();
            return;
        }
        CharSequence source = event.getPackageName();
        int type = event.getEventType();
        // A custom tab can hide its toolbar on scroll. Its activity still owns the same window.
        if (session.allowlist && session.allowBrowserTabs && source != null
                && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String owner = source.toString();
            String activity = String.valueOf(event.getClassName());
            if (browser(owner) && activity.endsWith("Activity")) {
                customTabWindow = BlockRules.customTabActivity(activity) ? event.getWindowId() : -1;
                customTabPackage = owner;
            }
        }
        if (queued) return;
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && (source == null || getPackageName().contentEquals(source))) return;
        handler.removeCallbacks(update);
        queued = true;
        handler.postDelayed(update, 30);
    }

    private void checkWindow() {
        queued = false;
        long now = SystemClock.elapsedRealtime();
        if (session == null) return;
        boolean softActive = session.active(now);
        if (!softActive && !session.test && session.timer.elapsedEnd > 0) {
            store.checkCompletion(new SessionClock(this), completion);
        }
        boolean hardActive = now < FocusService.settingsBlockedUntil;
        if (breakUntil > 0 && breakUntil <= now) {
            breakUntil = EmergencyBreaks.current(this).until;
            LockNotifications.showSoft(this, session, breakUntil);
            reportChange();
        }
        boolean paused = !session.test && breakUntil > now;
        boolean realSession = softActive && !session.test;
        history.sample(now, realSession && !paused ? session.timer.elapsedEnd : 0,
                realSession ? java.util.Collections.singletonMap(sessionKey, session.timer.elapsedEnd) : java.util.Collections.emptyMap());
        if (!softActive && !hardActive) {
            hideCover();
            return;
        }

        long delay = IDLE_CHECK_MS;
        if (keyguard.isKeyguardLocked()) hideCover();
        else if (navigationUntil > now) delay = navigationUntil - now;
        else delay = checkForeground(now, softActive && !paused, hardActive || (paused && softActive));
        if (paused) delay = Math.min(delay, breakUntil - now);
        if (softActive) delay = Math.min(delay, session.timer.elapsedEnd - now);
        if (hardActive) delay = Math.min(delay, FocusService.settingsBlockedUntil - now);
        handler.postDelayed(update, Math.max(1, delay));
    }

    private long checkForeground(long now, boolean softActive, boolean hardActive) {
        AccessibilityNodeInfo root = foregroundRoot();
        if (root == null) {
            navigation.reset();
            return recheckUnknownCover(now);
        }
        try {
            CharSequence packageName = root.getPackageName();
            if (packageName == null || packageName.length() == 0) {
                navigation.reset();
                return recheckUnknownCover(now);
            }
            String id = packageName.toString();
            int windowId = root.getWindowId();
            String title = null;
            String reason = "";
            boolean site = false;
            boolean settingsBlocked = settingsApps.contains(id);
            if (settingsBlocked) {
                title = "Android Settings is blocked";
            } else if (softActive && BlockRules.appBlocked(id, session.allowlist, session.apps, essentials)
                    && !(session.allowlist && session.allowBrowserTabs && browser(id) && customTab(root, id))) {
                title = appBlockTitle(id);
                if (session.allowlist) reason = "This app is outside your Allowlist.";
            } else if (softActive && !session.allowlist && !session.sites.isEmpty() && browser(id)) {
                site = true;
                Address address = browserAddress(root, id);
                boolean alreadyCovered = cover != null && windowId == coveredWindowId
                        && cover.previous.getVisibility() == View.VISIBLE;
                if (address == Address.EDITING) {
                    // Text typed in the address bar is neither a page nor a strict-mode failure.
                    navigation.reset();
                } else if (address == Address.UNKNOWN) {
                    if (BlockRules.blockUnreadableBrowser(id, session.blockUnverifiable)) {
                        long wait = navigation.unreadableDelay(id, windowId, now);
                        if (wait > 0 && !alreadyCovered) return Math.min(wait, IDLE_CHECK_MS);
                        title = "Browser is blocked";
                        reason = "Focus Lock cannot read this browser's address bar.";
                    } else {
                        navigation.reset();
                        if (alreadyCovered) return recheckUnknownCover(now);
                    }
                } else if (!address.domain.isEmpty() && BlockRules.matches(address.domain, session.sites)) {
                    long wait = navigation.blockedHostDelay(id, windowId, address.domain, now);
                    if (!alreadyCovered && wait > 0) return wait;
                    title = address.domain + " is blocked";
                } else {
                    navigation.reset();
                }
            } else {
                navigation.reset();
            }
            if (title != null) {
                // Keep a test cover available long enough to use its End test action.
                boolean autoReturn = !site && ((settingsBlocked && hardActive)
                        || (softActive && !session.test && session.returnHome));
                if (!autoReturn) appReturn.reset();
                long remaining = autoReturn ? appReturn.remaining(id, now) : Long.MAX_VALUE;
                long end = settingsBlocked
                        ? Math.max(session.timer.elapsedEnd, FocusService.settingsBlockedUntil)
                        : session.timer.elapsedEnd;
                showCover(windowId, title, reason, site, end, now);
                if (cover != null && !site && !settingsBlocked) {
                    cover.appPackage = id;
                    cover.appTitle = title;
                }
                if (cover != null && remaining != Long.MAX_VALUE) {
                    if (remaining > 0) return Math.min(remaining, IDLE_CHECK_MS);
                    long current = SystemClock.elapsedRealtime();
                    if ((!session.active(current) && !(settingsBlocked && current < FocusService.settingsBlockedUntil))
                            || keyguard.isKeyguardLocked()) return 1L;
                    if (returnHome()) {
                        navigationUntil = current + NAVIGATION_SETTLE_MS;
                        return NAVIGATION_SETTLE_MS;
                    }
                }
            } else {
                if (cover != null && !release.confirmed(windowId, now)) return CoverRelease.CONFIRM_MS;
                hideCover();
            }
            return IDLE_CHECK_MS;
        } finally {
            root.recycle();
        }
    }

    private long recheckUnknownCover(long now) {
        if (cover == null) return IDLE_CHECK_MS;
        // A full-screen overlay can prevent Android from exposing the app below it.
        // Give transitions time to settle, then detach it and query the foreground again.
        if (release.shouldRecheckWithoutCover(now)) detachCover();
        return CoverRelease.CONFIRM_MS;
    }

    private AccessibilityNodeInfo foregroundRoot() {
        List<AccessibilityWindowInfo> visible = getWindows();
        try {
            AccessibilityWindowInfo focused = null;
            AccessibilityWindowInfo active = null;
            AccessibilityWindowInfo covered = null;
            AccessibilityWindowInfo top = null;
            for (AccessibilityWindowInfo window : visible) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (top == null) top = window;
                if (focused == null && window.isFocused()) focused = window;
                if (active == null && window.isActive()) active = window;
                if (window.getId() == coveredWindowId) covered = window;
            }
            if (focused != null && focused != covered) {
                AccessibilityNodeInfo root = focused.getRoot();
                if (root != null) return root;
            }
            if (active != null && active != covered && active != focused) {
                AccessibilityNodeInfo root = active.getRoot();
                if (root != null) return root;
            }
            // The cover can leave the old app with stale focus flags. A newly
            // visible app above it takes precedence if it covers the old app.
            if (cover != null && top != null && top != covered
                    && (covered == null || obscures(top, covered))) {
                return top.getRoot();
            }
            if (covered != null) return covered.getRoot();
        } finally {
            for (AccessibilityWindowInfo window : visible) window.recycle();
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityWindowInfo window = root.getWindow();
        boolean application = window != null && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION;
        if (window != null) window.recycle();
        if (application) return root;
        root.recycle();
        return null;
    }

    private static boolean obscures(AccessibilityWindowInfo upper, AccessibilityWindowInfo lower) {
        Rect intersection = new Rect();
        Rect original = new Rect();
        upper.getBoundsInScreen(intersection);
        lower.getBoundsInScreen(original);
        if (!intersection.intersect(original)) return false;
        long overlap = (long) intersection.width() * intersection.height();
        long area = (long) original.width() * original.height();
        return overlap * 10 >= area * 9;
    }

    private boolean browser(String id) {
        return CHROMIUM.contains(id) || FIREFOX.contains(id) || BlockRules.SAMSUNG_INTERNET.equals(id)
                || detectedBrowsers.contains(id);
    }

    private boolean customTab(AccessibilityNodeInfo root, String id) {
        // Match browser-owned custom tab controls, never a generic address bar or page content.
        return (customTabWindow >= 0 && root.getWindowId() == customTabWindow && id.equals(customTabPackage))
                || hasVisibleView(root, id, "custom_tabs_toolbar")
                || hasVisibleView(root, id, "custom_tabs_close_button")
                || hasVisibleView(root, id, "mozac_feature_customtabs_close");
    }

    private Address browserAddress(AccessibilityNodeInfo root, String id) {
        if (CHROMIUM.contains(id)) {
            Address address = findAddress(root, id, "url_bar");
            if (address == Address.UNKNOWN && hasVisibleView(root, id, "search_box_text")) return Address.LOCAL;
            return address;
        }
        if (FIREFOX.contains(id)) {
            Address address = findAddress(root, id, "mozac_browser_toolbar_url_view", "mozac_browser_toolbar_edit_url_view");
            return address == Address.UNKNOWN ? findFirefoxAddress(root, id) : address;
        }
        if (BlockRules.SAMSUNG_INTERNET.equals(id)) {
            return findAddress(root, id, "location_bar_edit_text", "url_bar");
        }
        return Address.UNKNOWN;
    }

    private boolean hasVisibleView(AccessibilityNodeInfo root, String id, String resource) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id + ":id/" + resource);
        boolean found = false;
        for (AccessibilityNodeInfo node : nodes) {
            found |= node.isVisibleToUser();
            node.recycle();
        }
        return found;
    }

    private Address findAddress(AccessibilityNodeInfo root, String id, String... resources) {
        Address result = Address.UNKNOWN;
        for (String resource : resources) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id + ":id/" + resource);
            for (AccessibilityNodeInfo node : nodes) {
                if (result == Address.UNKNOWN || result == Address.EDITING) {
                    Address address = readAddress(node);
                    if (address != Address.UNKNOWN) result = address;
                }
                node.recycle();
            }
            if (result != Address.UNKNOWN && result != Address.EDITING) return result;
        }
        return result;
    }

    private Address findFirefoxAddress(AccessibilityNodeInfo root, String id) {
        // Firefox's Compose toolbar exposes virtual accessibility nodes with test-tag IDs.
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        int collected = 1;
        try {
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.remove();
                try {
                    String type = String.valueOf(node.getClassName());
                    if ("android.webkit.WebView".equals(type) || "org.mozilla.geckoview.GeckoView".equals(type)) continue;
                    String viewId = node.getViewIdResourceName();
                    if ("ADDRESSBAR_URL".equals(viewId) || (id + ":id/ADDRESSBAR_URL").equals(viewId)
                            || "ADDRESSBAR_URL_BOX".equals(viewId) || (id + ":id/ADDRESSBAR_URL_BOX").equals(viewId)) {
                        Address address = readAddress(node);
                        if (address != Address.UNKNOWN) return address;
                    }
                    for (int i = 0; i < node.getChildCount() && collected < FIREFOX_SCAN_LIMIT; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) {
                            pending.add(child);
                            collected++;
                        }
                    }
                } finally {
                    node.recycle();
                }
            }
            return Address.UNKNOWN;
        } finally {
            for (AccessibilityNodeInfo node : pending) node.recycle();
        }
    }

    private Address readAddress(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser()) return Address.UNKNOWN;
        if (node.isEditable() && node.isFocused()) return Address.EDITING;
        CharSequence value = node.getText();
        if (value == null || node.isShowingHintText()) return Address.UNKNOWN;
        if (LOCAL_PAGES.contains(value.toString().trim())) return Address.LOCAL;
        String domain = BlockRules.browserDomain(value);
        return domain.isEmpty() ? Address.UNKNOWN : new Address(domain);
    }

    private String appBlockTitle(String packageName) {
        if (cover != null && packageName.equals(cover.appPackage)) return cover.appTitle;
        String title;
        try {
            PackageManager packages = getPackageManager();
            String label = packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString().trim();
            title = label.isEmpty() ? "App is blocked" : label + " is blocked";
        } catch (PackageManager.NameNotFoundException unavailable) {
            title = "App is blocked";
        }
        return title;
    }

    private void showCover(int windowId, String title, String reason, boolean site, long end, long now) {
        release.reset();
        boolean testing = session.test && session.active(now);
        long wallNow = System.currentTimeMillis();
        long remaining = Math.max(0, end - SystemClock.elapsedRealtime());
        String until = ClockLabels.unlockTime(wallNow + remaining, wallNow, ClockLabels.is24Hour(this));
        if (cover != null) {
            coveredWindowId = windowId;
            cover.updateMessage(title, reason, until, site, testing);
            return;
        }
        BlockCover layout = new BlockCover();
        layout.updateMessage(title, reason, until, site, testing);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.setTitle("Focus Lock");
        try {
            windows.addView(layout, params);
            cover = layout;
            coveredWindowId = windowId;
            layout.requestFocus();
        } catch (WindowManager.BadTokenException unavailable) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private void leaveCover(int action) {
        if (action == GLOBAL_ACTION_HOME) {
            scheduleCoverCheck(returnHome() ? NAVIGATION_SETTLE_MS : 0);
        } else {
            // Back must reach the browser. The focusable cover consumes it otherwise.
            detachCover();
            scheduleCoverCheck(performGlobalAction(action) ? NAVIGATION_SETTLE_MS : 0);
        }
    }

    private boolean returnHome() {
        appReturn.markAttempted();
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) return false;
        detachCover();
        return true;
    }

    private void scheduleCoverCheck(long delay) {
        navigationUntil = SystemClock.elapsedRealtime() + delay;
        handler.removeCallbacks(update);
        queued = true;
        handler.postDelayed(update, delay);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (cover != null) cover.updateAppearance(configuration);
    }

    private void hideCover() {
        appReturn.reset();
        navigationUntil = 0L;
        detachCover();
    }

    private void detachCover() {
        release.reset();
        BlockCover visible = cover;
        cover = null;
        coveredWindowId = -1;
        if (visible != null) windows.removeViewImmediate(visible);
    }

    @Override public void onInterrupt() {
        hideCover();
    }

    @Override public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    private void disconnect() {
        handler.removeCallbacksAndMessages(null);
        if (history != null) history.stop();
        if (store != null) {
            store.unobserve(changed);
            store = null;
        }
        session = null;
        test = null;
        if (connected != this) return;
        connected = null;
        LockNotifications.hideSoft(this);
        hideCover();
        reportChange();
    }

    private final class BlockCover extends ScrollView {
        private final LinearLayout content;
        private final TextView heading;
        private final TextView deadline;
        private final TextView detail;
        private final Button previous;
        private final Button home;
        private final Button stopTest;
        private Button primary;
        private String appPackage;
        private String appTitle;

        BlockCover() {
            super(SoftBlocker.this);
            setFillViewport(true);
            setFocusableInTouchMode(true);
            setClickable(true);

            content = new LinearLayout(getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(24), dp(28), dp(24), dp(28));
            addView(content, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

            ImageView icon = new ImageView(getContext());
            icon.setImageResource(R.drawable.ic_focus);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            content.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

            heading = addText(28, 16);
            heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            deadline = addText(18, 6);
            deadline.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            detail = addText(15, 8);

            stopTest = addButton("End test", SoftBlocker::endTest, 24);
            previous = addButton("Previous page", () -> leaveCover(GLOBAL_ACTION_BACK), 8);
            home = addButton("Go Home", () -> leaveCover(GLOBAL_ACTION_HOME), 8);
        }

        void updateMessage(String title, String reason, String until, boolean site, boolean testing) {
            updateText(heading, title);
            updateText(deadline, "Until " + until);
            updateText(detail, testing ? (reason.isEmpty() ? "Soft Lock test" : "Soft Lock test\n" + reason) : reason);
            detail.setVisibility(detail.length() == 0 ? GONE : VISIBLE);
            stopTest.setVisibility(testing ? VISIBLE : GONE);
            previous.setVisibility(site ? VISIBLE : GONE);
            Button nextPrimary = testing ? stopTest : site ? previous : home;
            if (primary != nextPrimary) {
                primary = nextPrimary;
                updateAppearance(getResources().getConfiguration());
            }
        }

        void updateAppearance(Configuration configuration) {
            boolean dark = (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int[] backdrop = dark ? new int[]{0xff203b32, 0xff161e1b, 0xff131816}
                    : new int[]{0xffdeede6, 0xffeff3ed, 0xfff6f5f1};
            setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, backdrop));
            heading.setTextColor(dark ? 0xffecf2ec : 0xff202925);
            deadline.setTextColor(dark ? 0xff8dd6b8 : 0xff194a3d);
            detail.setTextColor(dark ? 0xffb5c2b8 : 0xff53615a);
            for (Button button : Arrays.asList(stopTest, previous, home)) {
                boolean emphasized = button == primary;
                int text = emphasized ? (dark ? 0xff09382a : 0xffffffff) : (dark ? 0xffcdeedf : 0xff194a3d);
                int fill = emphasized ? (dark ? 0xff8dd6b8 : 0xff176552) : (dark ? 0xff203b32 : 0xffe0efe7);
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(fill);
                shape.setCornerRadius(dp(16));
                button.setTextColor(text);
                button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x26000000),
                        shape, null));
            }
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) leaveCover(GLOBAL_ACTION_HOME);
                return true;
            }
            // Let the user navigate overlay buttons with a hardware keyboard.
            super.dispatchKeyEvent(event);
            return true;
        }

        private TextView addText(int size, int topMargin) {
            TextView view = new TextView(getContext());
            view.setTextSize(size);
            view.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(topMargin);
            content.addView(view, params);
            return view;
        }

        private Button addButton(String text, Runnable action, int topMargin) {
            Button button = new Button(getContext());
            button.setAllCaps(false);
            button.setText(text);
            button.setElevation(0);
            button.setStateListAnimator(null);
            button.setMinHeight(dp(48));
            button.setOnClickListener(view -> action.run());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dp(224), LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(topMargin);
            content.addView(button, params);
            return button;
        }

        private int dp(int size) {
            return Math.round(size * getResources().getDisplayMetrics().density);
        }

        private void updateText(TextView view, String text) {
            if (!text.contentEquals(view.getText())) view.setText(text);
        }
    }
}
