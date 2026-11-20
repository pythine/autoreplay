# WA Auto Reply (Kotlin + Groq Llama)

## Cara membuat project
1. Android Studio → New Project → **Empty Views Activity** (bukan Compose).
   Name: `WaAutoReply`, Package: `com.example.waautoreply`, Language: Kotlin, Min SDK 26.
2. Salin file dari ZIP ini ke project (timpa jika sudah ada):
   - `app/build.gradle.kts`
   - `app/src/main/AndroidManifest.xml`
   - `app/src/main/res/layout/activity_main.xml`
   - `app/src/main/res/values/strings.xml` dan `themes.xml`
   - `app/src/main/java/com/example/waautoreply/*.kt`
   (Hapus file `ui/theme` bawaan jika ada. Jika versi plugin di `build.gradle.kts` root berbeda, biarkan milik project Anda.)
3. Klik **Sync Project with Gradle Files**.

## Menjalankan
1. Hubungkan HP Android (aktifkan Developer options + USB debugging), klik **Run**.
2. Dapatkan API key di https://console.groq.com/keys, tempel di aplikasi, klik **Simpan Pengaturan**.
3. Aktifkan switch **Auto-Reply** → tombol akan membuka pengaturan Akses Notifikasi → aktifkan **WA Auto Reply**.
   - Android 13+ (jika di-install via APK/debug dan opsi redup): App info → ⋮ → **Allow restricted settings**.
4. Matikan optimasi baterai untuk aplikasi ini agar service tidak dimatikan sistem.

## Menguji
- Dari HP lain (atau WhatsApp Web), kirim chat pribadi ke nomor di HP ini. HP ini harus dalam keadaan notifikasi WhatsApp muncul (jangan sedang membuka chat tersebut, dan jangan di-mute).
- Dalam 2–3 detik + waktu respons AI, balasan terkirim otomatis. Riwayat tampil di layar utama.
- Uji juga: pesan di grup (harus diabaikan), panggilan, dan pesan foto tanpa teks (diabaikan).
