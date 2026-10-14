package net.kdt.pojavlaunch.newfag;

import android.app.Activity;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * NewFag hazir paketi: ilk acilista (ve paket.json surumu artinca) NewFag profilini kurar.
 * - Fabric surum dosyasini indirir (meta.fabricmc.net)
 * - assets/newfag/paket.json'daki modlari profilin mods klasorune hash dogrulamasiyla indirir
 * - "NewFag" profilini olusturur/gunceller; oyun acilinca dogrudan sunucuya baglanir (gameArgs)
 * Oyuncunun kapattigi (.disabled) ya da kendi ekledigi modlara dokunulmaz.
 */
public class NewFagPaket {
    private static final String TAG = "NewFagPaket";
    private static final String PREF_SURUM = "newfag_paket_surum";
    private static final String PREF_PROFIL = "newfag_profil_anahtari";
    private static final String KAYIT = ".newfag-paket.json";
    private static volatile boolean calisiyor = false;

    /** LauncherActivity acilirken cagrilir; gerekmiyorsa hicbir sey yapmaz. */
    public static void gerekirseKur(Activity activity) {
        if (calisiyor) return;
        final JsonObject paket;
        try {
            paket = paketOku(activity);
        } catch (IOException e) {
            Log.e(TAG, "paket.json okunamadi", e);
            return;
        }
        SharedPreferences pref = LauncherPreferences.DEFAULT_PREF;
        int surum = paket.get("surum").getAsInt();
        String anahtar = pref.getString(PREF_PROFIL, null);
        LauncherProfiles.load();
        boolean profilVar = anahtar != null && LauncherProfiles.mainProfileJson.profiles.containsKey(anahtar);
        if (profilVar && pref.getInt(PREF_SURUM, 0) >= surum) return;

        calisiyor = true;
        new Thread(() -> {
            try {
                kur(activity, paket);
            } catch (Exception e) {
                Log.e(TAG, "NewFag paketi kurulamadi", e);
                Tools.showError(activity, R.string.newfag_kurulum_hata, e);
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                calisiyor = false;
            }
        }, "NewFagPaket").start();
    }

    private static JsonObject paketOku(Activity activity) throws IOException {
        try (InputStream in = activity.getAssets().open("newfag/paket.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static void ilerleme(int yuzde, String ne) {
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, Math.max(0, Math.min(100, yuzde)), R.string.newfag_kurulum, ne);
    }

    private static void kur(Activity activity, JsonObject paket) throws Exception {
        String mc = paket.get("mc").getAsString();
        String fabric = paket.get("fabric").getAsString();
        String sunucu = paket.get("sunucu").getAsString();
        String klasor = paket.get("klasor").getAsString();
        JsonArray modlar = paket.getAsJsonArray("mods");

        // 1) Fabric surum dosyasi
        ilerleme(0, "Fabric " + fabric);
        String fabricJson = DownloadUtils.downloadString(FabriclikeUtils.FABRIC_UTILS.createJsonDownloadUrl(mc, fabric));
        String surumId = JsonParser.parseString(fabricJson).getAsJsonObject().get("id").getAsString();
        File surumDosyasi = new File(Tools.DIR_HOME_VERSION, surumId + "/" + surumId + ".json");
        if (!surumDosyasi.getParentFile().isDirectory() && !surumDosyasi.getParentFile().mkdirs())
            throw new IOException("Klasor olusturulamadi: " + surumDosyasi.getParent());
        Tools.write(surumDosyasi.getAbsolutePath(), fabricJson);

        // 2) Modlar
        File oyunKlasoru = new File(Tools.DIR_GAME_HOME, klasor);
        File modKlasoru = new File(oyunKlasoru, "mods");
        if (!modKlasoru.isDirectory() && !modKlasoru.mkdirs()) throw new IOException("Klasor olusturulamadi: " + modKlasoru);
        Set<String> yeni = new HashSet<>();
        for (JsonElement e : modlar) yeni.add(e.getAsJsonObject().get("dosya").getAsString());
        // eski paketten kalip yeni listede olmayan modlari kaldir (sadece bizim kurduklarimiz)
        File kayit = new File(modKlasoru, KAYIT);
        if (kayit.isFile()) {
            try {
                for (JsonElement e : JsonParser.parseString(Tools.read(kayit.getAbsolutePath())).getAsJsonArray()) {
                    String ad = e.getAsString();
                    if (!yeni.contains(ad) && guvenliAd(ad)) {
                        new File(modKlasoru, ad).delete();
                        new File(modKlasoru, ad + ".disabled").delete();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "eski paket kaydi okunamadi", e);
            }
        }
        int toplam = modlar.size(), i = 0;
        byte[] tampon = new byte[65536];
        for (JsonElement e : modlar) {
            JsonObject m = e.getAsJsonObject();
            String ad = m.get("dosya").getAsString();
            String hash = m.get("hash").getAsString();
            final int sira = i++;
            if (!guvenliAd(ad)) continue;
            File hedef = new File(modKlasoru, ad);
            if (new File(modKlasoru, ad + ".disabled").exists()) continue; // oyuncu kapatmis
            if (hedef.isFile() && hashTutuyor(hedef, hash)) continue;
            String gorunen = m.get("ad").getAsString();
            ilerleme(sira * 100 / toplam, gorunen);
            File gecici = new File(modKlasoru, ad + ".indiriliyor");
            IOException son = null;
            for (int deneme = 0; deneme < 3; deneme++) {
                try {
                    DownloadUtils.downloadFileMonitored(m.get("url").getAsString(), gecici, tampon,
                            (curr, max) -> ilerleme((int) ((sira + (max > 0 ? (float) curr / max : 0f)) * 100f / toplam), gorunen));
                    if (!hashTutuyor(gecici, hash)) throw new IOException(gorunen + " dogrulanamadi (dosya bozuk indi)");
                    if (hedef.exists()) hedef.delete();
                    if (!gecici.renameTo(hedef)) throw new IOException("Dosya tasinamadi: " + hedef);
                    son = null;
                    break;
                } catch (IOException ex) {
                    son = ex;
                    gecici.delete();
                }
            }
            if (son != null) throw son;
        }
        JsonArray kayitListesi = new JsonArray();
        for (String ad : yeni) kayitListesi.add(ad);
        Tools.write(kayit.getAbsolutePath(), kayitListesi.toString());

        // 3) Profil
        ilerleme(100, "profil");
        SharedPreferences pref = LauncherPreferences.DEFAULT_PREF;
        LauncherProfiles.load();
        Map<String, MinecraftProfile> profiller = LauncherProfiles.mainProfileJson.profiles;
        String anahtar = pref.getString(PREF_PROFIL, null);
        boolean ilkKurulum = anahtar == null || !profiller.containsKey(anahtar);
        if (ilkKurulum) anahtar = LauncherProfiles.getFreeProfileKey();
        MinecraftProfile p = ilkKurulum ? new MinecraftProfile() : profiller.get(anahtar);
        p.name = paket.has("profil") ? paket.get("profil").getAsString() : "NewFag";
        p.lastVersionId = surumId;
        p.gameDir = "./" + klasor;
        p.icon = "fabric";
        p.gameArgs = "--quickPlayMultiplayer " + sunucu;
        profiller.put(anahtar, p);
        LauncherProfiles.write();

        SharedPreferences.Editor ed = pref.edit()
                .putString(PREF_PROFIL, anahtar)
                .putInt(PREF_SURUM, paket.get("surum").getAsInt());
        if (ilkKurulum) ed.putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, anahtar);
        ed.apply();

        final String secilen = anahtar;
        final boolean sec = ilkKurulum;
        activity.runOnUiThread(() -> {
            if (sec) ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, secilen);
            View v = activity.findViewById(R.id.mc_version_spinner);
            if (v instanceof mcVersionSpinner) ((mcVersionSpinner) v).reloadProfiles();
        });
    }

    private static boolean guvenliAd(String ad) {
        return ad != null && ad.matches("[\\w.+\\-() ]{1,160}") && !ad.startsWith(".");
    }

    /** "sha512:hex" / "sha256:hex" / "sha1:hex" */
    static boolean hashTutuyor(File dosya, String spec) {
        int ayrac = spec.indexOf(':');
        if (ayrac < 0) return false;
        String alg = spec.substring(0, ayrac).toLowerCase(Locale.ROOT);
        String beklenen = spec.substring(ayrac + 1).toLowerCase(Locale.ROOT);
        String javaAd = alg.equals("sha512") ? "SHA-512" : alg.equals("sha256") ? "SHA-256" : alg.equals("sha1") ? "SHA-1" : null;
        if (javaAd == null) return false;
        try (InputStream in = new FileInputStream(dosya)) {
            MessageDigest md = MessageDigest.getInstance(javaAd);
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString().equals(beklenen);
        } catch (Exception e) {
            return false;
        }
    }
}
