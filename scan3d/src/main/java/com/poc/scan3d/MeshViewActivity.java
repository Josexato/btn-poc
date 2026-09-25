package com.poc.scan3d;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.Toast;

import com.poc.scan3d.core.Mesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Visor OpenGL ES 2 del último modelo: arrastrar = orbitar, pellizcar = zoom. */
public class MeshViewActivity extends Activity {

    private GLSurfaceView gl;
    private final Renderer renderer = new Renderer();
    private float lastX, lastY;
    private ScaleGestureDetector scale;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Mesh m = MainActivity.lastMesh();
        if (m == null) {
            Toast.makeText(this, "Aún no hay modelo", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        renderer.setMesh(m);
        gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        gl.setRenderer(renderer);
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        scale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                renderer.dist = Math.max(0.5f, Math.min(6f, renderer.dist / d.getScaleFactor()));
                gl.requestRender();
                return true;
            }
        });
        gl.setOnTouchListener((v, e) -> {
            scale.onTouchEvent(e);
            if (e.getPointerCount() == 1) {
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    renderer.yaw += (e.getX() - lastX) * 0.4f;
                    renderer.pitch = Math.max(-89, Math.min(89, renderer.pitch + (e.getY() - lastY) * 0.4f));
                    gl.requestRender();
                }
                lastX = e.getX();
                lastY = e.getY();
            }
            return true;
        });
        setContentView(gl);
        Toast.makeText(this, m.describe(), Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gl != null) gl.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gl != null) gl.onResume();
    }

    static final class Renderer implements GLSurfaceView.Renderer {
        float yaw = 30, pitch = 25, dist = 2.2f;
        private FloatBuffer data;
        private int count, prog;
        private float radius = 1, cx, cy, cz;
        private final float[] proj = new float[16], view = new float[16], mvp = new float[16];

        void setMesh(Mesh m) {
            float[] b = m.bounds();
            cx = (b[0] + b[1]) / 2; cy = (b[2] + b[3]) / 2; cz = (b[4] + b[5]) / 2;
            radius = Math.max(1, Math.max(b[1] - b[0], Math.max(b[3] - b[2], b[5] - b[4])) / 2);
            // Vértices desindexados (sin índices de 32 bits en ES 2): pos, normal, color.
            count = m.tri.length;
            float[] v = new float[count * 9];
            for (int i = 0; i < count; i++) {
                int k = m.tri[i] * 3;
                for (int a = 0; a < 3; a++) {
                    v[i * 9 + a] = m.pos[k + a];
                    v[i * 9 + 3 + a] = m.nrm[k + a];
                    v[i * 9 + 6 + a] = m.col == null ? 0.7f : m.col[k + a];
                }
            }
            data = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            data.put(v).position(0);
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            GLES20.glClearColor(0.12f, 0.12f, 0.14f, 1);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            String vs = "uniform mat4 uMvp; uniform vec3 uEye;"
                    + "attribute vec3 aPos; attribute vec3 aNrm; attribute vec3 aCol;"
                    + "varying vec3 vCol;"
                    + "void main(){ vec3 l = normalize(uEye - aPos);"
                    + " float d = abs(dot(normalize(aNrm), l));"
                    + " vCol = aCol * (0.35 + 0.65 * d); gl_Position = uMvp * vec4(aPos, 1.0); }";
            String fs = "precision mediump float; varying vec3 vCol; void main(){ gl_FragColor = vec4(vCol, 1.0); }";
            prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs));
            GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs));
            GLES20.glLinkProgram(prog);
        }

        private static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            return s;
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            GLES20.glViewport(0, 0, w, h);
            Matrix.perspectiveM(proj, 0, 40, w / (float) h, 0.05f * radius, 50 * radius);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            float d = dist * radius * 1.6f;
            float ex = cx + (float) (d * Math.cos(pr) * Math.sin(yr));
            float ey = cy - (float) (d * Math.cos(pr) * Math.cos(yr));
            float ez = cz + (float) (d * Math.sin(pr));
            Matrix.setLookAtM(view, 0, ex, ey, ez, cx, cy, cz, 0, 0, 1); // Z hacia arriba
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
            GLES20.glUseProgram(prog);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uEye"), ex, ey, ez);
            int stride = 9 * 4;
            String[] names = {"aPos", "aNrm", "aCol"};
            for (int a = 0; a < 3; a++) {
                int loc = GLES20.glGetAttribLocation(prog, names[a]);
                data.position(a * 3);
                GLES20.glVertexAttribPointer(loc, 3, GLES20.GL_FLOAT, false, stride, data);
                GLES20.glEnableVertexAttribArray(loc);
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count);
        }
    }
}
