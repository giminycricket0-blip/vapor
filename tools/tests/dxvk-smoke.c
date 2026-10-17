/* Account-free Windows graphics/cache-path probe. Run only in a test container.
 * Build as i686 and x86_64. Writes <exe>.result and a uniquely named cache marker.
 * --stress exercises repeated uploads/presents and two swapchain resizes.
 * A missing graphics device is a reported failure, never a successful rendering test. */
#define COBJMACROS
#include <windows.h>
#include <d3d11.h>
#include <d3dcompiler.h>
#include <stdio.h>
#include <string.h>

static int check_cache_path(FILE *out) {
    char path[1024], value[768], bytes[64] = {0};
    const char marker[] = "Vapor DXVK guest cache path\n";
    DWORD n = GetEnvironmentVariableA("DXVK_SHADER_CACHE_PATH", value, sizeof(value));
    if (!n || n >= sizeof(value)) {
        fprintf(out, "cache_path=FAIL missing-or-too-long\n");
        return 0;
    }
    snprintf(path, sizeof(path), "%s\\vapor-dxvk-probe-%u.cache-check", value,
             (unsigned)(sizeof(void *) * 8));
    HANDLE file = CreateFileA(path, GENERIC_READ | GENERIC_WRITE, 0, NULL,
                              CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) {
        fprintf(out, "cache_path=FAIL error=%lu path=%s\n", GetLastError(), path);
        return 0;
    }
    DWORD written = 0, read = 0;
    int ok = WriteFile(file, marker, sizeof(marker) - 1, &written, NULL) &&
             written == sizeof(marker) - 1 && FlushFileBuffers(file);
    SetFilePointer(file, 0, NULL, FILE_BEGIN);
    ok &= ReadFile(file, bytes, sizeof(bytes), &read, NULL) &&
          read == sizeof(marker) - 1 && !memcmp(bytes, marker, read);
    CloseHandle(file);
    fprintf(out, "cache_path=%s path=%s\n", ok ? "PASS" : "FAIL", path);
    return ok;
}

static int check_graphics(FILE *out, HWND window, int stress) {
    ID3D11Device *device = NULL;
    ID3D11DeviceContext *context = NULL;
    IDXGISwapChain *swap = NULL;
    ID3D11Texture2D *back = NULL, *staging = NULL;
    ID3D11RenderTargetView *target = NULL;
    ID3D11VertexShader *vs = NULL;
    ID3D11PixelShader *ps = NULL;
    ID3D11Buffer *color_buffer = NULL;
    ID3DBlob *vs_code = NULL, *ps_code = NULL, *errors = NULL;
    HMODULE compiler = NULL;
    pD3DCompile compile = NULL;
    D3D_FEATURE_LEVEL level = 0;
    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_0};
    DXGI_SWAP_CHAIN_DESC swap_desc = {0};
    D3D11_TEXTURE2D_DESC texture_desc = {0};
    D3D11_MAPPED_SUBRESOURCE mapped = {0};
    const char vertex[] = "float4 main(uint id : SV_VertexID) : SV_Position {"
        "float2 p = float2((id << 1) & 2, id & 2); return float4(p * float2(2,-2) + float2(-1,1), 0, 1); }";
    const char pixel[] = "cbuffer Color : register(b0) { float4 color; };"
        "float4 main() : SV_Target { return color; }";
    const float clear[] = {0, 0, 0, 1};
    unsigned char observed[4] = {0};
    unsigned frames = stress ? 240 : 1, readbacks = 0, resizes = 0;
    int ok = 0;
    HRESULT hr = E_FAIL;
#define CHECK(call) do { hr = (call); if (FAILED(hr)) { \
    fprintf(out, "graphics=FAIL step=%s hresult=0x%08lx\n", #call, (unsigned long)hr); \
    goto done; } } while (0)
    swap_desc.BufferDesc.Width = 320;
    swap_desc.BufferDesc.Height = 240;
    swap_desc.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    swap_desc.SampleDesc.Count = 1;
    swap_desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swap_desc.BufferCount = 1;
    swap_desc.OutputWindow = window;
    swap_desc.Windowed = TRUE;
    swap_desc.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;
    CHECK(D3D11CreateDeviceAndSwapChain(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0,
        levels, 2, D3D11_SDK_VERSION, &swap_desc, &swap, &device, &level, &context));
    fprintf(out, "device=PASS feature_level=0x%x\n", (unsigned)level);
    CHECK(IDXGISwapChain_GetBuffer(swap, 0, &IID_ID3D11Texture2D, (void **)&back));
    CHECK(ID3D11Device_CreateRenderTargetView(device, (ID3D11Resource *)back, NULL, &target));
    compiler = LoadLibraryA("d3dcompiler_47.dll");
    if (compiler) compile = (pD3DCompile)(void *)GetProcAddress(compiler, "D3DCompile");
    if (!compile) {
        fprintf(out, "graphics=FAIL compiler_unavailable error=%lu\n", GetLastError());
        goto done;
    }
    CHECK(compile(vertex, strlen(vertex), "vapor-probe-vs", NULL, NULL, "main", "vs_4_0", 0, 0, &vs_code, &errors));
    if (errors) { ID3D10Blob_Release(errors); errors = NULL; }
    CHECK(compile(pixel, strlen(pixel), "vapor-probe-ps", NULL, NULL, "main", "ps_4_0", 0, 0, &ps_code, &errors));
    CHECK(ID3D11Device_CreateVertexShader(device, ID3D10Blob_GetBufferPointer(vs_code),
        ID3D10Blob_GetBufferSize(vs_code), NULL, &vs));
    CHECK(ID3D11Device_CreatePixelShader(device, ID3D10Blob_GetBufferPointer(ps_code),
        ID3D10Blob_GetBufferSize(ps_code), NULL, &ps));
    D3D11_BUFFER_DESC buffer_desc = {0};
    buffer_desc.ByteWidth = 16;
    buffer_desc.Usage = D3D11_USAGE_DEFAULT;
    buffer_desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    CHECK(ID3D11Device_CreateBuffer(device, &buffer_desc, NULL, &color_buffer));
    for (unsigned frame = 0; frame < frames; frame++) {
        if (stress && (frame == 80 || frame == 160)) {
            UINT width = frame == 80 ? 256 : 384, height = frame == 80 ? 192 : 256;
            ID3D11DeviceContext_ClearState(context);
            ID3D11DeviceContext_Flush(context);
            ID3D11RenderTargetView_Release(target); target = NULL;
            ID3D11Texture2D_Release(back); back = NULL;
            ID3D11Texture2D_Release(staging); staging = NULL;
            CHECK(IDXGISwapChain_ResizeBuffers(swap, 1, width, height, DXGI_FORMAT_UNKNOWN, 0));
            CHECK(IDXGISwapChain_GetBuffer(swap, 0, &IID_ID3D11Texture2D, (void **)&back));
            CHECK(ID3D11Device_CreateRenderTargetView(device, (ID3D11Resource *)back, NULL, &target));
            resizes++;
        }
        ID3D11Texture2D_GetDesc(back, &texture_desc);
        D3D11_VIEWPORT viewport = {0, 0, (FLOAT)texture_desc.Width, (FLOAT)texture_desc.Height, 0, 1};
        if (!staging) {
            texture_desc.Usage = D3D11_USAGE_STAGING;
            texture_desc.BindFlags = 0;
            texture_desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
            texture_desc.MiscFlags = 0;
            CHECK(ID3D11Device_CreateTexture2D(device, &texture_desc, NULL, &staging));
        }
        unsigned char expected[] = {64, 128, 191, 255};
        if (stress) {
            expected[0] = (unsigned char)(32 + (frame * 37) % 192);
            expected[1] = (unsigned char)(32 + (frame * 71) % 192);
            expected[2] = (unsigned char)(32 + (frame * 97) % 192);
        }
        float color[4];
        for (unsigned i = 0; i < 4; i++) color[i] = expected[i] / 255.0f;
        ID3D11DeviceContext_UpdateSubresource(context, (ID3D11Resource *)color_buffer, 0, NULL, color, 0, 0);
        ID3D11DeviceContext_PSSetConstantBuffers(context, 0, 1, &color_buffer);
        ID3D11DeviceContext_OMSetRenderTargets(context, 1, &target, NULL);
        ID3D11DeviceContext_RSSetViewports(context, 1, &viewport);
        ID3D11DeviceContext_IASetPrimitiveTopology(context, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        ID3D11DeviceContext_VSSetShader(context, vs, NULL, 0);
        ID3D11DeviceContext_PSSetShader(context, ps, NULL, 0);
        ID3D11DeviceContext_ClearRenderTargetView(context, target, clear);
        ID3D11DeviceContext_Draw(context, 3, 0);
        if (frame % 16 == 0 || (frame + 1) % 80 == 0 || frame + 1 == frames) {
            ID3D11DeviceContext_CopyResource(context, (ID3D11Resource *)staging, (ID3D11Resource *)back);
            CHECK(ID3D11DeviceContext_Map(context, (ID3D11Resource *)staging, 0, D3D11_MAP_READ, 0, &mapped));
            int pixels_ok = 1;
            for (unsigned sample = 0; sample < 5; sample++) {
                unsigned x = texture_desc.Width * (sample == 4 ? 2 : 1 + (sample & 1) * 2) / 4;
                unsigned y = texture_desc.Height * (sample == 4 ? 2 : 1 + (sample >> 1) * 2) / 4;
                memcpy(observed, (unsigned char *)mapped.pData + y * mapped.RowPitch + x * 4, 4);
                for (unsigned i = 0; i < 4; i++) {
                    int delta = (int)observed[i] - expected[i];
                    if (delta < -1 || delta > 1) pixels_ok = 0;
                }
            }
            ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)staging, 0);
            readbacks++;
            fprintf(out, "frame=%u size=%ux%u pixel=%u,%u,%u,%u expected=%u,%u,%u,%u check=%s\n",
                    frame, texture_desc.Width, texture_desc.Height, observed[0], observed[1], observed[2], observed[3],
                    expected[0], expected[1], expected[2], expected[3], pixels_ok ? "PASS" : "FAIL");
            fflush(out);
            if (!pixels_ok) goto done;
        }
        CHECK(IDXGISwapChain_Present(swap, 0, 0));
        MSG message;
        while (PeekMessageA(&message, NULL, 0, 0, PM_REMOVE)) {
            TranslateMessage(&message);
            DispatchMessageA(&message);
        }
    }
    ok = 1;
    fprintf(out, "graphics=PASS shader-draw-readback-present frames=%u readbacks=%u resizes=%u\n",
            frames, readbacks, resizes);
done:
    if (errors) {
        fprintf(out, "compiler_log=%.*s\n", (int)ID3D10Blob_GetBufferSize(errors),
                (char *)ID3D10Blob_GetBufferPointer(errors));
        ID3D10Blob_Release(errors);
    }
    if (vs) ID3D11VertexShader_Release(vs);
    if (ps) ID3D11PixelShader_Release(ps);
    if (color_buffer) ID3D11Buffer_Release(color_buffer);
    if (vs_code) ID3D10Blob_Release(vs_code);
    if (ps_code) ID3D10Blob_Release(ps_code);
    if (target) ID3D11RenderTargetView_Release(target);
    if (staging) ID3D11Texture2D_Release(staging);
    if (back) ID3D11Texture2D_Release(back);
    if (swap) IDXGISwapChain_Release(swap);
    if (context) ID3D11DeviceContext_Release(context);
    if (device) ID3D11Device_Release(device);
    if (compiler) FreeLibrary(compiler);
    return ok;
#undef CHECK
}

int main(int argc, char **argv) {
    char path[MAX_PATH + 16];
    DWORD length = GetModuleFileNameA(NULL, path, MAX_PATH);
    if (!length || length >= MAX_PATH) return 90;
    strcat(path, ".result");
    FILE *out = fopen(path, "w");
    if (!out) return 91;
    int stress = argc == 2 && !strcmp(argv[1], "--stress");
    if (argc > 1 && !stress) { fprintf(out, "result=FAIL usage: [--stress]\n"); fclose(out); return 92; }
    fprintf(out, "Vapor DXVK probe: %u-bit Windows guest\n", (unsigned)(sizeof(void *) * 8));
    fprintf(out, "mode=%s\n", stress ? "stress" : "smoke");
    int cache_ok = check_cache_path(out);
    fflush(out);
    WNDCLASSA cls = {0};
    cls.lpfnWndProc = DefWindowProcA;
    cls.hInstance = GetModuleHandleA(NULL);
    cls.lpszClassName = "VaporDxvkProbe";
    RegisterClassA(&cls);
    HWND window = CreateWindowA(cls.lpszClassName, "Vapor DXVK probe", WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT, CW_USEDEFAULT, 360, 300, NULL, NULL, cls.hInstance, NULL);
    if (window) { ShowWindow(window, SW_SHOW); UpdateWindow(window); }
    int graphics_ok = window && check_graphics(out, window, stress);
    if (window) DestroyWindow(window);
    fprintf(out, "result=%s cache=%s graphics=%s\n", cache_ok && graphics_ok ? "PASS" : "FAIL",
            cache_ok ? "PASS" : "FAIL", graphics_ok ? "PASS" : "FAIL");
    fclose(out);
    return !(cache_ok && graphics_ok);
}
