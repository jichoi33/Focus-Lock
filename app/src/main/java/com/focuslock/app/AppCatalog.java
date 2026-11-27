package com.focuslock.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.telecom.TelecomManager;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AppCatalog {
    static final class Entry {
        final String id;
        final String label;

        Entry(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    static Set<String> essentials(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> excluded = new HashSet<>(Arrays.asList(context.getPackageName(), "android",
                "com.android.systemui", "com.android.server.telecom",
                "com.android.phone", "com.android.dialer", "com.google.android.dialer",
                "com.samsung.android.dialer", "com.samsung.android.incallui",
                "com.android.permissioncontroller", "com.google.android.permissioncontroller"));
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        if (telecom != null) excluded.add(telecom.getDefaultDialerPackage());
        excluded.addAll(homeApps(pm));
        excluded.addAll(settingsApps(pm));
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("content://com.focuslock.app/update.apk"),
                        "application/vnd.android.package-archive");
        for (ResolveInfo handler : pm.queryIntentActivities(install,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY)) {
            String installer = handler.activityInfo.packageName;
            if (pm.checkPermission(android.Manifest.permission.INSTALL_PACKAGES, installer)
                    == PackageManager.PERMISSION_GRANTED) excluded.add(installer);
        }
        return excluded;
    }

    static void addAllowlistTools(Context context, Set<String> packages) {
        PackageManager pm = context.getPackageManager();
        for (String id : Arrays.asList(
                "com.samsung.android.app.smartcapture", "com.samsung.android.clipboarduiservice",
                "com.samsung.android.app.clipboardedge", "com.samsung.android.biometrics.app.setting",
                "com.android.intentresolver", "com.google.android.intentresolver")) {
            addSystemApp(pm, packages, id);
        }
        // A picker can also be a full gallery or file manager. Launcher apps remain user choices.
        for (Intent intent : Arrays.asList(new Intent(Intent.ACTION_CHOOSER),
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                new Intent("android.provider.action.PICK_IMAGES").setType("image/*"))) {
            for (ResolveInfo handler : pm.queryIntentActivities(intent,
                    PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY)) {
                String id = handler.activityInfo.packageName;
                if (pm.getLaunchIntentForPackage(id) == null) packages.add(id);
            }
        }
    }

    static Set<String> homeApps(PackageManager pm) {
        Set<String> packages = new HashSet<>();
        for (ResolveInfo home : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY)) {
            packages.add(home.activityInfo.packageName);
        }
        return packages;
    }

    static Set<String> settingsApps(PackageManager pm) {
        Set<String> packages = new HashSet<>();
        packages.add("com.android.settings");
        for (String action : Arrays.asList(Settings.ACTION_SETTINGS, "com.android.settings.action.SETTINGS_SEARCH",
                "android.settings.APP_SEARCH_SETTINGS")) {
            for (ResolveInfo settings : pm.queryIntentActivities(new Intent(action),
                    PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY)) {
                packages.add(settings.activityInfo.packageName);
            }
        }
        // AOSP Settings binds its suggestions service by component, without an intent filter.
        addSystemApp(pm, packages, "com.android.settings.intelligence");
        Intent suggestions = new Intent("android.service.settings.suggestions.SuggestionService");
        for (ResolveInfo service : pm.queryIntentServices(suggestions, PackageManager.MATCH_SYSTEM_ONLY)) {
            packages.add(service.serviceInfo.packageName);
        }
        return packages;
    }

    private static void addSystemApp(PackageManager pm, Set<String> packages, String id) {
        try {
            ApplicationInfo app = pm.getApplicationInfo(id, 0);
            if ((app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                packages.add(id);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // This system component is optional.
        }
    }

    static List<Entry> launchable(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> excluded = essentials(context);
        Map<String, Entry> entries = new LinkedHashMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo result : pm.queryIntentActivities(launcher, 0)) {
            String id = result.activityInfo.packageName;
            if (!excluded.contains(id)) entries.putIfAbsent(id, new Entry(id, result.loadLabel(pm).toString()));
        }
        List<Entry> list = new ArrayList<>(entries.values());
        Collator order = Collator.getInstance();
        list.sort((left, right) -> order.compare(left.label, right.label));
        return list;
    }

    static Set<String> browsers(Context context, boolean includeTabProviders) {
        Set<String> result = new HashSet<>();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        for (ResolveInfo handler : context.getPackageManager().queryIntentActivities(intent, 0)) {
            result.add(handler.activityInfo.packageName);
        }
        if (includeTabProviders) {
            Intent tabs = new Intent("android.support.customtabs.action.CustomTabsService");
            for (ResolveInfo provider : context.getPackageManager().queryIntentServices(tabs, 0)) {
                result.add(provider.serviceInfo.packageName);
            }
        }
        return result;
    }
}
