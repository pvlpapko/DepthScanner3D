precision mediump float;
varying vec3 v_Color;

void main() {
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    if (dot(p, p) > 1.0) {
        discard;
    }
    gl_FragColor = vec4(v_Color, 0.95);
}
