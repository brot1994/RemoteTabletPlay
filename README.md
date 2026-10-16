# Remote Tablet Play

Jam flip untuk tablet + musik/video (YouTube, YT Music, Spotify) di layar terbagi + pesan & pengumuman suara — semuanya bisa dikendalikan dari HP lewat internet.
Dikembangkan dari Flip Clock, tapi aplikasi terpisah (id.bram.remotetabletplay) sehingga Flip Clock lama tidak berubah.

## Isi repo
- `app/` – aplikasi Android. Tampilan jam di `app/src/main/assets/www/`.
- `app/src/main/assets/www/config.js` – alamat Firebase dan halaman remote.
- `docs/` – halaman remote (GitHub Pages): https://brot1994.github.io/RemoteTabletPlay/
- `.github/workflows/build.yml` – build APK otomatis setiap push ke `main`.

## Ambil APK
Tab Actions → run terbaru (hijau) → Artifacts → `TabletPlay-apk`.
Play Protect akan memblokir karena aplikasi meminta "akses notifikasi" (untuk kontrol musik):
matikan sementara Play Store → Play Protect → ⚙ → Pindai aplikasi, instal, lalu nyalakan lagi.

## Persiapan tablet (sekali)
1. Pengaturan aplikasi → Musik & suara pengumuman → Beri izin kontrol musik.
2. Suara Indonesia: mesin Google + unduh data suara Indonesia.
3. Layar terbagi: YouTube/YT Music/Spotify kiri, Tablet Play kanan → simpan sebagai App pair.

Firebase memakai database yang sama dengan Flip Clock (agi-flipclock), rules tidak perlu diubah.
Font: Oswald & Archivo (SIL OFL). QR: qrcode-generator (MIT).
