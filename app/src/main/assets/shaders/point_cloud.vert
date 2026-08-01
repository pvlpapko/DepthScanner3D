uniform mat4 u_Mvp;
uniform float u_PointSize;
attribute vec3 a_Position;
attribute vec3 a_Color;
varying vec3 v_Color;

void main() {
    gl_Position = u_Mvp * vec4(a_Position, 1.0);
    gl_PointSize = u_PointSize;
    v_Color = a_Color;
}
