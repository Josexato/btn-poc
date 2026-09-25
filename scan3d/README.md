# Scan3D: objeto 3D a partir de fotos girando sobre un escenario impreso

App Android (Java y Camera2, sin librerías nativas) pensada para Galaxy S26 Ultra; funciona en cualquier Android 11+.
La app toma 1 foto por segundo mientras giras el objeto sobre un escenario impreso y reconstruye un modelo 3D en mm, que exporta como **OBJ** y **PLY** (con color) y como **STL**.

## Cómo funciona

| Paso | Técnica | Archivo |
|---|---|---|
| Pose de la cámara | 4 marcadores circulares → homografía → autocalibración de la focal y pose (R, t) | `core/MatDetector.java`, `core/CamModel.java` |
| Ángulo de giro (modo DISCO) | Anillo con **secuencia‑m de 63 bits**, leído por correlación circular. Da el ángulo absoluto (error de ~0.1° en las pruebas) aunque el objeto tape parte del anillo | `core/RingAngle.java` |
| Ángulo de giro (modo LIBRE) | **Momentos de Hu**: la silueta vuelve a parecerse a la inicial al completar 360°. Así se obtiene el período y se supone un giro uniforme | `core/HuMoments.java` |
| Silueta | Resta de fondo. En la zona del disco, el fondo se gira el mismo ángulo medido (fondo compensado en rotación) | `core/Segmenter.java` |
| Validación | Se descartan las fotos si la silueta toca el borde (una mano), si el área es anómala o si no se puede leer el anillo | `ScanSession.java` |
| 3D | Casco visual por tallado de vóxeles (pasada gruesa y fina) → Surface Nets → suavizado de Taubin → color por vértice | `core/Carver.java`, `core/SurfaceNets.java`, `core/Colorizer.java` |
| Multicámara | Flujos YUV de varias cámaras **físicas** de la cámara lógica trasera. Cada cámara se calibra por separado. Si el HAL rechaza la combinación, se usa solo la cámara lógica | `CameraRig.java` |
| Diagnóstico | Genera un JSON con IDs lógicos y físicos, focales, formatos (RAW10/12/16, DEPTH16…), OIS, capacidades y combinaciones simultáneas aceptadas | `CameraProbe.java` |

## Uso

1. **Imprimir escenario (PDF)** al 100 %, en A4 o Carta. Comprueba con una regla que la barra mida 100 mm.
   - Hoja 1: el tapete fijo.
   - Hoja 2: el disco. Recórtalo, pégalo sobre cartón y colócalo sobre el círculo del tapete. Si clavas una chincheta en el centro, el eje de giro queda fijo.
2. Pon el teléfono **en un trípode**, a unos 35–50 cm, mirando el tapete desde 20–40° de elevación. Deben verse los 4 marcadores. Usa luz difusa.
3. **Foto de fondo** con el disco puesto y **sin objeto**. En este paso se fijan la exposición, el enfoque y el balance de blancos.
4. Pon el objeto en el centro del disco y pulsa **Iniciar captura**. Gira el disco de a poco y suéltalo: la app dispara cuando la escena está quieta, como máximo 1 foto por segundo. Al completar 36/36 sectores se detiene sola.
5. (Recomendado) Haz una **nueva pasada** con el teléfono a otra altura: quita el objeto, toma otra foto de fondo, vuelve a ponerlo en el mismo sitio del disco y captura otra vuelta.
6. **Generar 3D**, luego **Ver 3D** y **Compartir**.

Los archivos quedan en `Android/data/com.poc.scan3d/files/scans/scan_<fecha>/`: las fotos, `modelo.obj`, `modelo.ply`, `modelo.stl` y `log.txt`.

## Límites conocidos

- **Casco visual**: no reproduce concavidades (el interior de una taza, por ejemplo). Con una sola altura de cámara queda un "techo" sobre las caras planas superiores, de unos `radio × tan(elevación)`. Una segunda pasada más baja lo corrige; en la prueba sintética, la altura pasa de 91 mm a 83 mm para un objeto real de 80 mm.
- Los objetos blancos sobre papel blanco y las sombras fuertes empeoran la silueta. Usa luz difusa y ajusta el **Umbral silueta**.
- Modo LIBRE: supone un giro uniforme en el tiempo y no distingue el sentido (hay que indicarlo). Solo usa la pasada 1.
- El color es aproximado: no se calcula la oclusión.
- Multicámara: las lentes traseras están a pocos cm entre sí, así que aportan poco punto de vista nuevo. Sí aportan más siluetas y más resolución. Qué combinaciones acepta el S26 Ultra depende del HAL: usa **Diagnóstico de cámaras**.

## Pruebas

```bash
./gradlew :scan3d:testDebugUnitTest
```

`PipelineTest` renderiza una escena sintética por trazado de rayos: tapete, disco y un objeto de dos cajas visto desde 2 alturas. Con ella verifica:

- detección de marcadores (< 1.5 px);
- focal (< 5 %);
- centro del disco (< 1.5 mm);
- ángulo (< 0.8°);
- IoU de las siluetas (> 0.9);
- período de Hu (36 ± 1);
- dimensiones del modelo (±3–5 mm);
- malla cerrada con volumen dentro del ±20 %.

La salida queda en `scan3d/build/test-output/sintetico.{obj,ply,stl}`.
