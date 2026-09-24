# Mirador 3D 📐

App **Android** para ver archivos **STL** y **OBJ**, pensada para impresión 3D. Todo el visor corre dentro de un WebView con [three.js](https://threejs.org/) empaquetado en el APK: **funciona sin internet y no pide ningún permiso**.

## ✨ Características

- Abre archivos **.stl** (binario y ASCII) y **.obj** desde el gestor de archivos del teléfono.
- Vista 3D con órbita, zoom y paneo táctil; rotación automática opcional y botón de centrar vista.
- **Panel de pieza** para impresión 3D: triángulos, vértices, dimensiones en mm, volumen, área y **peso estimado en PLA** (1,24 g/cm³ al 100% de relleno).
- Material sólido con selector de color o vista de normales, malla (wireframe) y rejilla de referencia.
- Tema claro/oscuro según el sistema.
- Sin permisos, sin red, sin telemetría: la pieza nunca sale del dispositivo.

## 📦 Descargar el APK

Cada push a `main` compila el APK automáticamente con GitHub Actions y lo publica en [**Releases**](../../releases). Descarga el `app-release.apk` más reciente e instálalo (Android 7.0+, hay que permitir "instalar apps desconocidas" porque va firmado con clave debug).

## 🛠 Compilar localmente

```bash
./gradlew assembleRelease
# APK en app/build/outputs/apk/release/app-release.apk
```

Requiere JDK 17 y el SDK de Android (compileSdk 34).

## 🧩 Estructura

```
app/src/main/assets/index.html   ← todo el visor (HTML + CSS + JS)
app/src/main/assets/lib/         ← three.js 0.147 + loaders STL/OBJ (offline)
app/src/main/java/.../MainActivity.java  ← WebView + selector de archivos nativo
.github/workflows/build.yml      ← CI: compila y publica el APK en Releases
```

La misma interfaz existe como página web: el visor también puede servirse estático (el `index.html` de assets no depende de nada nativo salvo el selector de archivos, que en navegador funciona solo).
