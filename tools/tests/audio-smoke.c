/* Account-free Wine/FEX audio check. Build for i686 and x86_64 with -lwinmm.
 * Writes <exe>.result. Exercises output only; never opens a microphone.
 * Stream position is not acoustic latency, measured sound quality or an underrun benchmark.
 * --invalid-device is a negative control and must fail to open its first stream. */
#include <windows.h>
#include <mmsystem.h>
#include <mmreg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned checks, failures;
static void note(FILE *out, const char *step, int ok, unsigned long value) {
    checks++;
    if (!ok) failures++;
    fprintf(out, "%s=%s value=%lu\n", step, ok ? "PASS" : "FAIL", value);
    fflush(out);
}

static int position(HWAVEOUT wave, DWORD rate, WORD frame_bytes, DWORD *frames) {
    MMTIME time = {0};
    time.wType = TIME_SAMPLES;
    if (waveOutGetPosition(wave, &time, sizeof(time)) != MMSYSERR_NOERROR) return 0;
    if (time.wType == TIME_SAMPLES) *frames = time.u.sample;
    else if (time.wType == TIME_BYTES) *frames = time.u.cb / frame_bytes;
    else if (time.wType == TIME_MS) *frames = (DWORD)((uint64_t)time.u.ms * rate / 1000);
    else return 0;
    return 1;
}

static int advances(HWAVEOUT wave, DWORD rate, WORD frame_bytes, DWORD before, DWORD *after) {
    ULONGLONG end = GetTickCount64() + 3000;
    do {
        if (!position(wave, rate, frame_bytes, after)) return 0;
        if (*after >= before + rate / 20) return 1; /* At least 50 ms of sample progress. */
        Sleep(10);
    } while (GetTickCount64() < end);
    return 0;
}

static int play_case(FILE *out, UINT device, DWORD rate, int floating, int cycle) {
    HWAVEOUT wave = NULL;
    WAVEFORMATEX format = {0};
    WAVEHDR header = {0};
    int prepared = 0, ok = 0;
    DWORD before = 0, after = 0;
    const DWORD frames = rate * 2; /* Two seconds; reset normally ends it much earlier. */
    const WORD frame_bytes = floating ? 8 : 4;
    void *pcm = calloc(frames, frame_bytes);
    if (!pcm) return 0;
    for (DWORD i = 0; i < frames; i++) {
        /* Quiet square-wave data. The emulator can run with its host audio disabled. */
        int sign = (i / (rate / 880)) & 1 ? 1 : -1;
        if (floating) {
            ((float *)pcm)[i * 2] = ((float *)pcm)[i * 2 + 1] = sign * 0.01f;
        } else {
            ((int16_t *)pcm)[i * 2] = ((int16_t *)pcm)[i * 2 + 1] = (int16_t)(sign * 300);
        }
    }
    format.wFormatTag = floating ? WAVE_FORMAT_IEEE_FLOAT : WAVE_FORMAT_PCM;
    format.nChannels = 2;
    format.nSamplesPerSec = rate;
    format.wBitsPerSample = floating ? 32 : 16;
    format.nBlockAlign = frame_bytes;
    format.nAvgBytesPerSec = rate * frame_bytes;
    fprintf(out, "case cycle=%d rate=%lu format=%s frames=%lu\n", cycle, rate,
            floating ? "float32" : "pcm16", frames);
    MMRESULT result = waveOutOpen(&wave, device, &format, 0, 0, CALLBACK_NULL);
    note(out, "open", result == MMSYSERR_NOERROR, result);
    if (result != MMSYSERR_NOERROR) goto done;
    header.lpData = pcm;
    header.dwBufferLength = frames * frame_bytes;
    result = waveOutPrepareHeader(wave, &header, sizeof(header));
    note(out, "prepare", result == MMSYSERR_NOERROR, result);
    if (result != MMSYSERR_NOERROR) goto done;
    prepared = 1;
    result = waveOutWrite(wave, &header, sizeof(header));
    note(out, "write", result == MMSYSERR_NOERROR, result);
    if (result != MMSYSERR_NOERROR) goto done;
    ok = advances(wave, rate, frame_bytes, 0, &after);
    note(out, "sample_progress", ok, after);
    if (!ok) goto done;
    result = waveOutPause(wave);
    note(out, "pause", result == MMSYSERR_NOERROR, result);
    if (result != MMSYSERR_NOERROR) { ok = 0; goto done; }
    Sleep(80);
    ok = position(wave, rate, frame_bytes, &before);
    Sleep(150);
    ok = ok && position(wave, rate, frame_bytes, &after) && before == after;
    note(out, "paused_position_stable", ok, after - before);
    if (!ok) goto done;
    result = waveOutRestart(wave);
    note(out, "restart", result == MMSYSERR_NOERROR, result);
    if (result != MMSYSERR_NOERROR) { ok = 0; goto done; }
    ok = advances(wave, rate, frame_bytes, after, &before);
    note(out, "resumed_progress", ok, before);
done:
    if (wave) {
        result = waveOutReset(wave);
        note(out, "reset", result == MMSYSERR_NOERROR, result);
        if (result != MMSYSERR_NOERROR) {
            fprintf(out, "result=FAIL unsafe-to-free-live-test-buffer\n"); fflush(out); ExitProcess(91);
        }
        DWORD reset_position = 1;
        int reset_ok = position(wave, rate, frame_bytes, &reset_position) && reset_position == 0;
        note(out, "reset_position_zero", reset_ok, reset_position);
        ok = ok && reset_ok;
        if (prepared) {
            note(out, "reset_returns_buffer", (header.dwFlags & WHDR_DONE) != 0, header.dwFlags);
            ok = ok && (header.dwFlags & WHDR_DONE) != 0;
            result = waveOutUnprepareHeader(wave, &header, sizeof(header));
            note(out, "unprepare", result == MMSYSERR_NOERROR, result);
            if (result != MMSYSERR_NOERROR) {
                fprintf(out, "result=FAIL unsafe-to-free-prepared-test-buffer\n"); fflush(out); ExitProcess(92);
            }
        }
        result = waveOutClose(wave);
        note(out, "close", result == MMSYSERR_NOERROR, result);
        ok = ok && result == MMSYSERR_NOERROR;
    }
    free(pcm);
    return ok;
}

int main(int argc, char **argv) {
    char path[MAX_PATH + 16], driver[128] = {0};
    DWORD length = GetModuleFileNameA(NULL, path, MAX_PATH);
    if (!length || length >= MAX_PATH) return 70;
    strcat(path, ".result");
    FILE *out = fopen(path, "w");
    if (!out) return 71;
    HKEY key;
    if (RegOpenKeyExA(HKEY_CURRENT_USER, "Software\\Wine\\Drivers", 0, KEY_QUERY_VALUE, &key) == ERROR_SUCCESS) {
        DWORD bytes = sizeof(driver);
        RegQueryValueExA(key, "Audio", NULL, NULL, (BYTE *)driver, &bytes);
        RegCloseKey(key);
    }
    driver[sizeof(driver) - 1] = 0;
    fprintf(out, "Vapor audio probe guest_bits=%u registry_driver=%s\n", (unsigned)(sizeof(void *) * 8), driver);
    UINT count = waveOutGetNumDevs();
    note(out, "output_devices", count > 0, count);
    fprintf(out, "driver_modules directaudio=%d pulse=%d alsa=%d\n",
            GetModuleHandleA("winedirectaudio.drv") != NULL,
            GetModuleHandleA("winepulse.drv") != NULL,
            GetModuleHandleA("winealsa.drv") != NULL);
    UINT device = argc == 2 && !strcmp(argv[1], "--invalid-device") ? (UINT)-2 : WAVE_MAPPER;
    int ok = count > 0;
    for (int cycle = 0; ok && cycle < 2; cycle++) {
        ok = play_case(out, device, 44100, 0, cycle) && play_case(out, device, 48000, 1, cycle);
    }
    fprintf(out, "result=%s checks=%u failures=%u\n", ok && !failures ? "PASS" : "FAIL", checks, failures);
    fclose(out);
    return ok && !failures ? 0 : 1;
}
