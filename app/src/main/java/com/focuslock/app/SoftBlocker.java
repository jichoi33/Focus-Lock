package com.focuslock.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private Set<String> homeApps;
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

    private boolean goingHome;
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
        homeApps = AppCatalog.homeApps(getPackageManager());
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
            breakUntil = 0;
            LockNotifications.showSoft(this, session, 0);
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
            release.reset();
            return IDLE_CHECK_MS;
        }
        try {
            CharSequence packageName = root.getPackageName();
            if (packageName == null || packageName.length() == 0) {
                release.reset();
                return IDLE_CHECK_MS;
            }
            String id = packageName.toString();
            int windowId = root.getWindowId();
            String reason = null;
            boolean site = false;
            boolean settingsBlocked = settingsApps.contains(id);
            if (settingsBlocked) {
                reason = "Android Settings is blocked during a lock.";
            } else if (softActive && BlockRules.appBlocked(id, session.allowlist, session.apps, essentials)
                    && !(session.allowlist && session.allowBrowserTabs && browser(id) && customTab(root, id))) {
                reason = session.allowlist ? "This app is not on your allowlist." : "This app is blocked.";
            } else if (softActive && !session.allowlist && !session.sites.isEmpty() && browser(id)) {
                site = true;
                Address address = browserAddress(root, id);
                boolean alreadyCovered = cover != null && windowId == coveredWindowId
                        && cover.previous.getVisibility() == View.VISIBLE;
                if (address == Address.EDITING) {
                    // Text typed in the address bar is neither a page nor a strict-mode failure.
                    navigation.reset();
                } else if (address == Address.UNKNOWN) {
                    if (alreadyCovered) {
                        release.reset();
                        return IDLE_CHECK_MS;
                    }
                    if (BlockRules.blockUnreadableBrowser(id, session.blockUnverifiable)) {
                        long wait = navigation.unreadableDelay(id, windowId, now);
                        if (wait > 0) return Math.min(wait, IDLE_CHECK_MS);
                        reason = "Focus Lock cannot read this browser's address bar.";
                    } else {
                        navigation.reset();
                    }
                } else if (!address.domain.isEmpty() && BlockRules.matches(address.domain, session.sites)) {
                    long wait = navigation.blockedHostDelay(id, windowId, address.domain, now);
                    if (!alreadyCovered && wait > 0) return wait;
                    reason = address.domain + " is blocked.";
                } else {
                    navigation.reset();
                }
            } else {
                navigation.reset();
            }
            if (reason != null) {
                showCover(windowId, reason, site);
                boolean returnHome = (settingsBlocked && hardActive) || (softActive && session.returnHome);
                if (!site && returnHome && cover != null) {
                    long remaining = appReturn.remaining(id, SystemClock.elapsedRealtime());
                    if (remaining > 0) return Math.min(remaining, IDLE_CHECK_MS);
                    long current = SystemClock.elapsedRealtime();
                    if ((session.active(current) || (settingsBlocked && current < FocusService.settingsBlockedUntil))
                            && !keyguard.isKeyguardLocked()) {
                        if (requestHome()) return CoverRelease.CONFIRM_MS;
                    }
                } else {
                    appReturn.reset();
                }
            } else if (cover != null) {
                if (!release.confirmed(windowId, now)) return CoverRelease.CONFIRM_MS;
                hideCover();
            }
            return IDLE_CHECK_MS;
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo foregroundRoot() {
        List<AccessibilityWindowInfo> visible = getWindows();
        try {
            AccessibilityWindowInfo active = null;
            AccessibilityWindowInfo covered = null;
            AccessibilityWindowInfo topApp = null;
            for (AccessibilityWindowInfo window : visible) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (topApp == null) topApp = window;
                if (window.isFocused()) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) return root;
                }
                if (window.getId() == coveredWindowId) covered = window;
                if (active == null && window.isActive()) active = window;
            }
            // A focusable accessibility overlay can become the active window itself.
            // Keep inspecting the underlying app while the overlay owns input focus.
            if (cover != null) {
                // Home can be visible underneath the cover before it receives input focus.
                if (goingHome && topApp != null && topApp != covered) {
                    AccessibilityNodeInfo home = topApp.getRoot();
                    if (home != null) {
                        if (homeApps.contains(String.valueOf(home.getPackageName()))) return home;
                        home.recycle();
                    }
                }
                return covered == null ? null : covered.getRoot();
            }
            if (active != null) {
                AccessibilityNodeInfo root = active.getRoot();
                if (root != null) return root;
            }
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

    private void showCover(int windowId, String text, boolean browser) {
        release.reset();
        if (cover != null) {
            if (coveredWindowId != windowId) goingHome = false;
            coveredWindowId = windowId;
            cover.updateMessage(text, browser);
            return;
        }
        BlockCover layout = new BlockCover(text, browser);
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
        boolean sent;
        if (action == GLOBAL_ACTION_HOME) {
            sent = requestHome();
        } else {
            hideCover();
            sent = performGlobalAction(action);
        }
        handler.removeCallbacks(update);
        queued = true;
        handler.postDelayed(update, sent ? 120L : 0L);
    }

    private boolean requestHome() {
        appReturn.markAttempted();
        boolean sent = performGlobalAction(GLOBAL_ACTION_HOME);
        goingHome |= sent;
        return sent;
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (cover != null) cover.updateAppearance(configuration);
    }

    private void hideCover() {
        release.reset();
        appReturn.reset();
        goingHome = false;
        if (cover == null) return;
        windows.removeViewImmediate(cover);
        cover = null;
        coveredWindowId = -1;
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

    private final class BlockCover extends LinearLayout {
        private final TextView heading;
        private final TextView detail;
        private final Button previous;
        private final Button home;
        private final Button stopTest;

        BlockCover(String message, boolean showPrevious) {
            super(SoftBlocker.this);
            setFocusableInTouchMode(true);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            int padding = Math.round(28 * getResources().getDisplayMetrics().density);
            setPadding(padding, padding, padding, padding);

            int iconSize = padding * 2;
            ImageView icon = new ImageView(getContext());
            icon.setImageResource(R.drawable.ic_focus);
            addView(icon, new LayoutParams(iconSize, iconSize));

            heading = new TextView(getContext());
            heading.setTextSize(30);
            heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            heading.setGravity(Gravity.CENTER);
            heading.setPadding(0, padding, 0, 0);
            addView(heading);

            detail = new TextView(getContext());
            detail.setTextSize(16);
            detail.setGravity(Gravity.CENTER);
            detail.setPadding(0, padding / 2, 0, padding);
            addView(detail);

            previous = addButton("Previous page", () -> leaveCover(GLOBAL_ACTION_BACK));
            home = addButton("Go Home", () -> leaveCover(GLOBAL_ACTION_HOME));
            stopTest = addButton("End test", SoftBlocker::endTest);
            updateMessage(message, showPrevious);
            updateAppearance(getResources().getConfiguration());
        }

        void updateMessage(String message, boolean showPrevious) {
            boolean testing = session.test && session.active(SystemClock.elapsedRealtime());
            boolean returning = !showPrevious && session.returnHome && !testing;
            String title = testing ? "Soft Lock test" : showPrevious ? "Website blocked" : "App blocked";
            if (returning) message += " Returning to Home…";
            home.setVisibility(returning ? GONE : VISIBLE);
            if (!title.contentEquals(heading.getText())) heading.setText(title);
            int stopVisibility = testing ? VISIBLE : GONE;
            if (stopTest.getVisibility() != stopVisibility) stopTest.setVisibility(stopVisibility);
            if (!message.contentEquals(detail.getText())) detail.setText(message);
            int visibility = showPrevious ? VISIBLE : GONE;
            if (previous.getVisibility() != visibility) previous.setVisibility(visibility);
        }

        void updateAppearance(Configuration configuration) {
            boolean dark = (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            setBackgroundColor(dark ? 0xff11141b : 0xfff3f4f8);
            heading.setTextColor(dark ? 0xfff1f4fb : 0xff171b25);
            detail.setTextColor(dark ? 0xffbbc4d5 : 0xff555e70);
            int buttonText = dark ? 0xff102e62 : 0xffffffff;
            int buttonFill = dark ? 0xffa9c7ff : 0xff1659cd;
            previous.setTextColor(buttonText);
            home.setTextColor(buttonText);
            stopTest.setTextColor(buttonText);
            ((GradientDrawable) previous.getBackground()).setColor(buttonFill);
            ((GradientDrawable) home.getBackground()).setColor(buttonFill);
            ((GradientDrawable) stopTest.getBackground()).setColor(buttonFill);
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

        private Button addButton(String text, Runnable action) {
            Button button = new Button(getContext());
            button.setAllCaps(false);
            button.setText(text);
            button.setElevation(0);
            button.setStateListAnimator(null);
            float density = getResources().getDisplayMetrics().density;
            button.setMinHeight(Math.round(48 * density));
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(16 * density);
            button.setBackground(background);
            button.setOnClickListener(view -> action.run());
            LayoutParams size = new LayoutParams(Math.round(208 * density), LayoutParams.WRAP_CONTENT);
            size.topMargin = Math.round(6 * density);
            addView(button, size);
            return button;
        }
    }
}
