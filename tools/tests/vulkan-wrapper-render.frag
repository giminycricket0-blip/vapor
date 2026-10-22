#version 450
layout(set = 0, binding = 0) uniform sampler2D texture_sampler;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 colour;
void main() {
    colour = texture(texture_sampler, uv);
}
