# Navaja ◆

App **Android** navaja suiza de visualización: abre **GAG · SDJF · SVG · STL · OBJ · PDF** en un solo lugar. Todo corre dentro de un WebView con las librerías empaquetadas en el APK: **funciona sin internet y no pide ningún permiso**.

## ✨ Qué abre

| Formato | Cómo lo muestra |
|---|---|
| `.gag` / `.sdjf` (AlmaGag) | Vista previa rápida del diagrama (nodos, conexiones, colores, flechas, iconos embebidos) + resumen del archivo. El render oficial sigue siendo el SVG que emite el motor AlmaGag. |
| `.svg` | Render fiel con pan y zoom (ideal para los SVG generados por AlmaGag). |
| `.stl` / `.obj` | Vista 3D con órbita táctil y panel de pieza para impresión 3D: triángulos, dimensiones en mm, volumen, área y peso estimado en PLA. |
| `.pdf` | Visor paginado con zoom y carga perezosa de páginas. |

## 📦 Descargar el APK

Cada push a `main` compila el APK con GitHub Actions y lo publica en [**Releases**](../../releases). Descarga el `app-release.apk` más reciente e instálalo (Android 7.0+; hay que permitir "instalar apps desconocidas" porque va firmado con clave debug).

## 🛠 Compilar localmente

```bash
./gradlew assembleRelease
# APK en app/build/outputs/apk/release/app-release.apk
```

Requiere JDK 17 y el SDK de Android (compileSdk 34).

## 🧩 Estructura

```
app/src/main/assets/index.html   ← toda la app (HTML + CSS + JS)
app/src/main/assets/lib/         ← three.js 0.147 + loaders, pdf.js 3.11 legacy (offline)
app/src/main/java/.../MainActivity.java  ← WebView + selector de archivos nativo
.github/workflows/build.yml      ← CI: compila y publica el APK en Releases
```

El mismo `index.html` funciona como página web estática en cualquier navegador (solo cambia que el selector de archivos es el del navegador).
