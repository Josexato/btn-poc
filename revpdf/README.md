# RevPDF 📄

App web de **OCR para PDFs** que funciona 100% en el navegador. Extrae el texto de PDFs escaneados (o con texto incrustado) sin subir nada a ningún servidor: la privacidad de tus documentos queda intacta.

## ✨ Características

- **Arrastra y suelta** un PDF o selecciónalo desde tu equipo.
- **OCR local** con [Tesseract.js](https://tesseract.projectnaptha.com/) — soporta español, inglés, portugués y francés (y combinaciones).
- **Detección inteligente**: si una página ya tiene texto incrustado, lo usa directamente y se salta el OCR (puedes forzar el OCR si lo prefieres).
- **Tres niveles de calidad** de renderizado (rápida / normal / alta) para balancear velocidad y precisión.
- **Resultados por página** con indicador de origen (OCR vs. texto original).
- **Exporta** todo el texto: cópialo al portapapeles o descárgalo como `.txt`.
- **Sin backend**: es un solo `index.html` estático. Nada se instala, nada se sube.

## 🚀 Uso

### En línea (GitHub Pages)

1. En la configuración del repo, ve a **Settings → Pages**.
2. En *Source* elige **Deploy from a branch** y selecciona la rama principal (carpeta `/root`).
3. Abre la URL que te da GitHub Pages.

### Local

No necesita build ni dependencias. Basta con servir el archivo:

```bash
# con Python
python3 -m http.server 8000
# o con Node
npx serve .
```

y abrir `http://localhost:8000`.

> Nota: se necesita conexión a internet la primera vez para descargar las librerías (PDF.js, Tesseract.js) y el modelo de idioma del OCR desde CDN. El procesamiento del PDF en sí es completamente local.

## 🧠 Cómo funciona

1. [PDF.js](https://mozilla.github.io/pdf.js/) carga el PDF y revisa cada página.
2. Si la página tiene texto incrustado suficiente, se extrae directamente.
3. Si no (página escaneada), la página se renderiza a un canvas y [Tesseract.js](https://tesseract.projectnaptha.com/) le aplica OCR con el idioma elegido.
4. El texto de todas las páginas se puede copiar o descargar como `.txt`.

## 📁 Estructura

```
index.html   ← toda la app (HTML + CSS + JS)
README.md
```
