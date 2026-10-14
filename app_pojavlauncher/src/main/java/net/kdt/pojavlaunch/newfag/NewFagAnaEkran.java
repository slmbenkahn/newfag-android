package net.kdt.pojavlaunch.newfag;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.util.Locale;

/**
 * NewFag ana ekrani: isimle giris, IP kopyalama, Discord ve sitedeki canli istatistikler
 * (masaustu launcher ile ayni API: launcher/api.php?islem=durum).
 */
public class NewFagAnaEkran {
    public static final String SUNUCU = "play.newfag.net";
    public static final String DISCORD = "https://discord.gg/newfaganarsi";
    private static final String API_DURUM = "https://newfag.net/launcher/api.php?islem=durum";
    private static final String PREF_ISIM = "newfag_isim";
    private static final long YENILEME_MS = 30_000;

    private final Fragment fragment;
    private final View kok;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean calisiyor = false;
    private final Runnable yenile = new Runnable() {
        @Override public void run() {
            if (!calisiyor) return;
            canliYukle();
            handler.postDelayed(this, YENILEME_MS);
        }
    };

    public NewFagAnaEkran(Fragment fragment, View kok) {
        this.fragment = fragment;
        this.kok = kok;

        TextView ip = kok.findViewById(R.id.nf_ip);
        if (ip != null) ip.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("NewFag", SUNUCU));
            Toast.makeText(v.getContext(), SUNUCU + " kopyalandı", Toast.LENGTH_SHORT).show();
        });

        View discord = kok.findViewById(R.id.discord_button);
        if (discord != null) discord.setOnClickListener(v -> Tools.openURL(fragment.requireActivity(), DISCORD));

        EditText isim = kok.findViewById(R.id.nf_isim);
        Button giris = kok.findViewById(R.id.nf_giris);
        if (isim != null && giris != null) {
            isim.setText(LauncherPreferences.DEFAULT_PREF.getString(PREF_ISIM, ""));
            giris.setOnClickListener(v -> girisYap(isim));
            isim.setOnEditorActionListener((v, actionId, e) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) { girisYap(isim); return true; }
                return false;
            });
        }
    }

    /** Isimle giris. Amethyst'in kurali korunur: cihazda once bir kez Microsoft hesabi (oyunun sahibi) olmali. */
    private void girisYap(EditText kutu) {
        Activity a = fragment.requireActivity();
        String ad = kutu.getText().toString().trim();
        if (!ad.matches("[A-Za-z0-9_]{3,16}")) {
            kutu.setError("3-16 karakter: harf, rakam ve _");
            return;
        }
        if (!Tools.hasOnlineProfile()) {
            new AlertDialog.Builder(a)
                    .setTitle("Bir kerelik doğrulama")
                    .setMessage("Telefonda oynamak için önce bir kez Minecraft'ı satın aldığın Microsoft hesabıyla giriş yapman gerekiyor. "
                            + "Sonra NewFag'e istediğin isimle buradan girebilirsin.")
                    .setPositiveButton("Microsoft ile giriş", (d, w) -> ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true))
                    .setNegativeButton("Vazgeç", null)
                    .show();
            return;
        }
        LauncherPreferences.DEFAULT_PREF.edit().putString(PREF_ISIM, ad).apply();
        ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{ad, ""});
        Toast.makeText(a, ad + " olarak giriş yapıldı", Toast.LENGTH_SHORT).show();
    }

    public void basla() {
        if (calisiyor) return;
        calisiyor = true;
        handler.post(yenile);
    }

    public void durdur() {
        calisiyor = false;
        handler.removeCallbacks(yenile);
    }

    private void canliYukle() {
        new Thread(() -> {
            try {
                JsonObject j = JsonParser.parseString(DownloadUtils.downloadString(API_DURUM)).getAsJsonObject();
                handler.post(() -> { if (calisiyor && fragment.isAdded()) ciz(j); });
            } catch (Exception e) {
                handler.post(() -> {
                    TextView t = kok.findViewById(R.id.nf_anarsi);
                    if (t != null && "Yükleniyor…".contentEquals(t.getText())) t.setText("Şu an yüklenemedi.");
                });
            }
        }, "NewFagCanli").start();
    }

    private void ciz(JsonObject j) {
        JsonObject sunucular = j.has("sunucular") ? j.getAsJsonObject("sunucular") : new JsonObject();
        JsonObject anarsi = sunucular.has("anarsi") ? sunucular.getAsJsonObject("anarsi") : new JsonObject();
        int toplam = 0;
        for (String k : sunucular.keySet()) toplam += sayi(sunucular.getAsJsonObject(k), "cevrimici");
        yaz(R.id.nf_cevrimici, "● " + toplam + " oyuncu çevrimiçi");

        StringBuilder a = new StringBuilder();
        a.append("Çevrimiçi: ").append(sayi(anarsi, "cevrimici"));
        a.append("\nToplam ölüm: ").append(String.format(Locale.ROOT, "%,d", sayi(anarsi, "toplam_olum")).replace(',', '.'));
        int mb = sayi(anarsi, "dunya_mb");
        a.append("\nDünya boyutu: ").append(mb >= 1024 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024f).replace('.', ',') : mb + " MB");
        if (anarsi.has("en_uzun") && anarsi.get("en_uzun").isJsonObject()) {
            JsonObject u = anarsi.getAsJsonObject("en_uzun");
            if (u.has("ad") && !u.get("ad").isJsonNull())
                a.append("\nEn uzun yaşayan: ").append(u.get("ad").getAsString()).append(" · ").append(sure(sayi(u, "saniye")));
        }
        yaz(R.id.nf_anarsi, a.toString());

        StringBuilder e = new StringBuilder();
        JsonArray elo = j.has("siralama") ? j.getAsJsonObject("siralama").getAsJsonArray("elo") : null;
        if (elo != null) {
            int n = 0;
            for (JsonElement x : elo) {
                if (n >= 5) break;
                JsonObject o = x.getAsJsonObject();
                if (n > 0) e.append('\n');
                e.append(++n).append(".  ").append(o.get("isim").getAsString()).append("  ·  ").append(sayi(o, "elo"));
            }
        }
        yaz(R.id.nf_elo, e.length() > 0 ? e.toString() : "Henüz maç yok.");

        StringBuilder s = new StringBuilder();
        JsonArray olumler = j.has("olumler") ? j.getAsJsonArray("olumler") : null;
        long simdi = System.currentTimeMillis() / 1000;
        if (olumler != null) {
            int n = 0;
            for (JsonElement x : olumler) {
                if (n >= 5) break;
                JsonObject o = x.getAsJsonObject();
                if (n++ > 0) s.append('\n');
                String olen = o.get("olen").getAsString();
                boolean katil = o.has("olduren") && !o.get("olduren").isJsonNull();
                s.append(katil ? o.get("olduren").getAsString() + " ⚔ " + olen : olen + " · " + o.get("sebep").getAsString());
                s.append("  (").append(once(simdi - sayi(o, "zaman"))).append(')');
            }
        }
        yaz(R.id.nf_olumler, s.length() > 0 ? s.toString() : "Henüz ölüm yok.");
    }

    private void yaz(int id, String metin) {
        TextView t = kok.findViewById(id);
        if (t != null) t.setText(metin);
    }

    private static int sayi(JsonObject o, String k) {
        try { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0; } catch (Exception e) { return 0; }
    }

    private static String sure(int sn) {
        if (sn >= 86400) return (sn / 86400) + " gün";
        if (sn >= 3600) return (sn / 3600) + " sa";
        if (sn >= 60) return (sn / 60) + " dk";
        return sn + " sn";
    }

    private static String once(long sn) {
        if (sn < 0) sn = 0;
        if (sn < 60) return sn + " sn";
        if (sn < 3600) return (sn / 60) + " dk";
        if (sn < 86400) return (sn / 3600) + " sa";
        return (sn / 86400) + " gün";
    }
}
