#!/usr/bin/env python3
"""Generate the app's notification sounds from scratch.

res/raw/imsg_receive.wav and imsg_send.wav used to be Apple's own sounds,
which cannot be published, so both are synthesized here with nothing but the
Python standard library (wave + math).  Running this script overwrites the
two files; the output is deterministic, so the committed WAVs can always be
regenerated.

Both files: 44.1 kHz, 16-bit PCM, mono, peak at about -3 dBFS, a 5 ms fade in
and a smooth exponential decay (ending in a short raised-cosine release to
exactly zero) so neither end clicks.

  imsg_receive.wav  soft two-note chime, ~330 ms: E6 (1318.5 Hz) for the
                    first ~150 ms crossfading into G6 (1568 Hz); each note
                    carries a second harmonic at -12 dB for warmth.
  imsg_send.wav     short rising blip, ~160 ms: a sine sweeping
                    880 Hz -> 1760 Hz with a quick decay.

Usage:  python3 tools/make_sounds.py [output_dir]
        (default output_dir is app/src/main/res/raw, relative to this file)
"""

import math
import os
import sys
import wave

SAMPLE_RATE = 44100
PEAK_DBFS = -3.0
FADE_IN_S = 0.005      # 5 ms attack on every sound
RELEASE_S = 0.015      # final ramp to exactly 0 so the file never ends mid-cycle
HARMONIC_DB = -12.0    # second-harmonic level for the chime notes


def db(x_db):
    """Decibels -> linear amplitude."""
    return 10.0 ** (x_db / 20.0)


def raised_cosine(x):
    """Smooth 0 -> 1 ramp for x in [0, 1], clamped outside that range."""
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    return 0.5 - 0.5 * math.cos(math.pi * x)


def edge_envelope(t, duration):
    """5 ms raised-cosine fade in, and a short release to zero at the end."""
    return raised_cosine(t / FADE_IN_S) * raised_cosine((duration - t) / RELEASE_S)


def note(freq, t, harmonic=db(HARMONIC_DB)):
    """A sine plus a quieter second harmonic (the 'warmth')."""
    return (math.sin(2.0 * math.pi * freq * t)
            + harmonic * math.sin(4.0 * math.pi * freq * t))


def receive_chime():
    """Two-note chime: E6 for ~150 ms, crossfading into G6, ~330 ms total."""
    duration = 0.330
    f1, f2 = 1318.5, 1568.0          # E6 -> G6
    switch = 0.150                   # where the second note takes over
    xfade = 0.030                    # crossfade width, centred on `switch`
    tau1, tau2 = 0.110, 0.060        # exponential decay constants per note
    xfade_start = switch - xfade / 2.0
    n = int(round(duration * SAMPLE_RATE))
    out = []
    for i in range(n):
        t = i / SAMPLE_RATE
        w2 = raised_cosine((t - xfade_start) / xfade)   # 0 -> 1 across the crossfade
        w1 = 1.0 - w2
        a1 = math.exp(-t / tau1)
        a2 = math.exp(-max(0.0, t - xfade_start) / tau2)
        s = w1 * a1 * note(f1, t) + w2 * a2 * note(f2, t)
        out.append(s * edge_envelope(t, duration))
    return normalize(out)


def send_blip():
    """Rising blip: sine gliding 880 -> 1760 Hz with a quick decay, ~160 ms."""
    duration = 0.160
    f_start, f_end = 880.0, 1760.0   # one octave up
    sweep = 0.120                    # the glide finishes here, then holds f_end
    tau = 0.045                      # quick decay
    n = int(round(duration * SAMPLE_RATE))
    out = []
    phase = 0.0                      # phase accumulator keeps the sweep continuous
    for i in range(n):
        t = i / SAMPLE_RATE
        x = min(1.0, t / sweep)
        f = f_start * (f_end / f_start) ** x        # exponential (musical) glide
        s = math.sin(phase) * math.exp(-t / tau)
        out.append(s * edge_envelope(t, duration))
        phase += 2.0 * math.pi * f / SAMPLE_RATE
    return normalize(out)


def normalize(samples, peak_dbfs=PEAK_DBFS):
    """Scale so the loudest sample sits at peak_dbfs; return 16-bit ints."""
    peak = max(abs(s) for s in samples) or 1.0
    gain = db(peak_dbfs) * 32767.0 / peak
    return [max(-32768, min(32767, int(round(s * gain)))) for s in samples]


def write_wav(path, samples):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(b"".join(v.to_bytes(2, "little", signed=True) for v in samples))


def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    out_dir = argv[1] if len(argv) > 1 else os.path.join(
        here, "..", "app", "src", "main", "res", "raw")
    out_dir = os.path.normpath(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    for name, generate in (("imsg_receive.wav", receive_chime),
                           ("imsg_send.wav", send_blip)):
        path = os.path.join(out_dir, name)
        samples = generate()
        write_wav(path, samples)
        print(f"wrote {path}: {len(samples)} frames, "
              f"{len(samples) / SAMPLE_RATE:.3f} s")


if __name__ == "__main__":
    main(sys.argv)
