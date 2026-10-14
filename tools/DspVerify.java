/**
 * DSP 算法 JVM 独立验证（与 Kotlin 实现逐行对照的 Java 镜像）。
 * 用 F:\vibe coding工程\jdk17 的 javac/java 直接跑，绕开 subst 盘的 Gradle worker classpath 坑。
 *
 * javac -encoding UTF-8 DspVerify.java && java DspVerify
 */
public class DspVerify {

    // ==== Fft（对照 Fft.kt）====
    static class Fft {
        final int n;
        final float[] cos, sin, workRe, workIm;
        Fft(int n) {
            this.n = n;
            cos = new float[n / 2];
            sin = new float[n / 2];
            for (int i = 0; i < n / 2; i++) {
                cos[i] = (float) Math.cos(2 * Math.PI * i / n);
                sin[i] = (float) Math.sin(2 * Math.PI * i / n);
            }
            workRe = new float[n];
            workIm = new float[n];
        }
        void transform(float[] re, float[] im, boolean inverse) {
            int i = 0;
            for (int j = 1; j < n; j++) {
                int bit = n >> 1;
                for (;;) {
                    if ((i & bit) != 0) { i ^= bit; bit >>= 1; } else break;
                }
                i |= bit;
                if (j < i) {
                    float t = re[j]; re[j] = re[i]; re[i] = t;
                    t = im[j]; im[j] = im[i]; im[i] = t;
                }
            }
            for (int len = 2; len <= n; len <<= 1) {
                int half = len >> 1;
                int step = n / len;
                for (int k = 0; k < n; k += len) {
                    for (int j = 0; j < half; j++) {
                        float c = cos[j * step];
                        float s = inverse ? -sin[j * step] : sin[j * step];
                        int l = k + j, r = k + j + half;
                        float tr = re[r] * c + im[r] * s;
                        float ti = im[r] * c - re[r] * s;
                        re[r] = re[l] - tr;
                        im[r] = im[l] - ti;
                        re[l] += tr;
                        im[l] += ti;
                    }
                }
            }
        }
        float[] powerSpectrum(float[] iq) {
            for (int s = 0; s < n; s++) { workRe[s] = iq[s * 2]; workIm[s] = iq[s * 2 + 1]; }
            transform(workRe, workIm, false);
            float[] out = new float[n];
            for (int b = 0; b < n; b++) out[b] = workRe[b] * workRe[b] + workIm[b] * workIm[b];
            return out;
        }
    }

    // ==== FmDemod（对照 Demodulators.kt FmDemod）====
    static class FmDemod {
        float lastPhase = 0;
        float demodulate(float i, float q) {
            float phase = (float) Math.atan2(q, i);
            float d = phase - lastPhase;
            lastPhase = phase;
            if (d > Math.PI) d -= 2 * (float) Math.PI;
            else if (d < -Math.PI) d += 2 * (float) Math.PI;
            return d / (float) Math.PI;
        }
    }

    // ==== StereoDecoder（对照 Demodulators.kt StereoDecoder）====
    static class Biquad {
        float b0 = 1, b1, b2, a1, a2, x1, x2, y1, y2;
        static Biquad lowpass(float fc, float fs, float q) {
            Biquad b = new Biquad();
            double w = 2 * Math.PI * fc / fs;
            double cw = Math.cos(w), sw = Math.sin(w);
            double alpha = sw / (2 * q);
            b.b0 = (float) ((1 - cw) / 2);
            b.b1 = (float) (1 - cw);
            b.b2 = (float) ((1 - cw) / 2);
            b.a1 = (float) (-2 * cw);
            b.a2 = (float) (1 - alpha);
            float g = (float) (1 + alpha);
            b.b0 /= g; b.b1 /= g; b.b2 /= g; b.a1 /= g; b.a2 /= g;
            return b;
        }
        static Biquad bandpass(float fc, float fs, float q) {
            Biquad b = new Biquad();
            double w = 2 * Math.PI * fc / fs;
            double cw = Math.cos(w), sw = Math.sin(w);
            double alpha = sw / (2 * q);
            b.b0 = (float) (sw / 2);
            b.b1 = 0;
            b.b2 = (float) (-sw / 2);
            b.a1 = (float) (-2 * cw);
            b.a2 = (float) (1 - alpha);
            float g = (float) (1 + alpha);
            b.b0 /= g; b.b1 /= g; b.b2 /= g; b.a1 /= g; b.a2 /= g;
            return b;
        }
        float process(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return y;
        }
    }

    static class StereoDecoder {
        final Biquad pilotBp = Biquad.bandpass(19000f, 256000f, 12f);
        final Biquad loopLp = Biquad.lowpass(400f, 256000f, 0.7071f);
        final Biquad subLp = Biquad.lowpass(15000f, 256000f, 0.7071f);
        final Biquad sumLpL = Biquad.lowpass(15000f, 256000f, 0.7071f);
        final Biquad sumLpR = Biquad.lowpass(15000f, 256000f, 0.7071f);
        double phase = 0, freqInteg = 0;
        final double baseInc = 2 * Math.PI * 19000 / 256000;
        float lockSmooth = 0;
        static final float PILOT_ON = 0.008f;

        float[] decode(float m) {
            float pilot = pilotBp.process(m);
            // 正交鉴相：pilot·sin(φ)，锁定时均值为 0（导频带通 −90° 与 VCO 稳态偏移抵消点）
            float pd = pilot * (float) Math.sin(phase);
            float err = loopLp.process(pd);
            freqInteg = freqInteg * 0.99999 + err * 5e-6;
            phase += baseInc + err * 0.02 + freqInteg;
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
            if (phase < 0) phase += 2 * Math.PI;
            float pilotEnv = Math.abs(pilot);
            lockSmooth = pilotEnv > PILOT_ON
                ? Math.min(lockSmooth + PILOT_ON / pilotEnv * 0.02f, 1f)
                : lockSmooth * 0.995f;
            if (lockSmooth < 0.3f) return new float[]{m, m};
            // 38k 开关解调：sub = LP(m·cos(2φ))（VCO 稳态偏移由环路自动补偿）
            float sub = subLp.process((float) (m * Math.cos(2 * phase)));
            float l = sumLpL.process(m + sub);
            float r = sumLpR.process(m - sub);
            return new float[]{l, r};
        }
    }

    static int fails = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name + "  " + detail);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        // 1) FFT 单频音峰值 bin
        {
            int n = 256;
            Fft fft = new Fft(n);
            float fs = 1024, f0 = 128;
            float[] iq = new float[n * 2];
            for (int s = 0; s < n; s++) {
                double ph = 2 * Math.PI * f0 * s / fs;
                iq[s * 2] = (float) Math.cos(ph);
                iq[s * 2 + 1] = (float) Math.sin(ph);
            }
            float[] out = fft.powerSpectrum(iq);
            int maxBin = 0; float maxV = 0;
            for (int k = 0; k < n; k++) if (out[k] > maxV) { maxV = out[k]; maxBin = k; }
            check("FFT 单频音 bin", maxBin == 32, "maxBin=" + maxBin + " (期望 32)");
        }
        // 2) FFT 负频率镜像 bin
        {
            int n = 128;
            Fft fft = new Fft(n);
            float fs = 1000, f0 = -125;
            float[] iq = new float[n * 2];
            for (int s = 0; s < n; s++) {
                double ph = 2 * Math.PI * f0 * s / fs;
                iq[s * 2] = (float) Math.cos(ph);
                iq[s * 2 + 1] = (float) Math.sin(ph);
            }
            float[] out = fft.powerSpectrum(iq);
            int maxBin = 0; float maxV = 0;
            for (int k = 0; k < n; k++) if (out[k] > maxV) { maxV = out[k]; maxBin = k; }
            check("FFT 负频镜像 bin", maxBin == 112, "maxBin=" + maxBin + " (期望 112)");
        }
        // 3) FM 鉴频恒定频偏 → 恒定输出
        {
            FmDemod d = new FmDemod();
            double fs = 256000, dev = 75000;
            double phase = 0;
            float last = 0;
            for (int s = 0; s < 10000; s++) {
                phase += 2 * Math.PI * dev / fs;
                float v = d.demodulate((float) Math.cos(phase), (float) Math.sin(phase));
                if (s > 100) last = v;
            }
            float expected = (float) (dev / (fs / 2));
            check("FM 恒偏输出", Math.abs(last - expected) < 0.02f,
                "last=" + last + " 期望=" + expected);
        }
        // 4) 立体声 PLL：锁定导频 + L/R 分离
        {
            double fs = 256000;
            StereoDecoder dec = new StereoDecoder();
            double sumL2 = 0, sumR2 = 0;
            int n = 0;
            double p38 = 0, p19 = 0;
            double phaseAlignAccum = 0; int phaseAlignN = 0;
            for (int s = 0; s < 512000; s++) {  // 2 秒
                double t = s / fs;
                double l = 0.8 * Math.sin(2 * Math.PI * 400 * t);
                double r = 0.2 * Math.sin(2 * Math.PI * 400 * t);
                p38 += 2 * Math.PI * 38000 / fs; if (p38 > 2 * Math.PI) p38 -= 2 * Math.PI;
                p19 += 2 * Math.PI * 19000 / fs; if (p19 > 2 * Math.PI) p19 -= 2 * Math.PI;
                float mpx = (float) ((l + r) / 2 + (l - r) * Math.cos(p38) + 0.1 * Math.cos(p19));
                float[] o = dec.decode(mpx);
                if (s > 256000) { sumL2 += o[0] * o[0]; sumR2 += o[1] * o[1]; n++; }
                if (s > 256000) { phaseAlignAccum += Math.cos(2 * dec.phase - p38); phaseAlignN++; }
                if (s % 64000 == 0) {
                    double d = (dec.phase - p19) % (2 * Math.PI);
                    System.out.printf("  t=%.2fs  φ−p19=%.3f rad  freqInteg=%.3e  lock=%.2f%n",
                        s / fs, d, dec.freqInteg, dec.lockSmooth);
                }
            }
            double rmsL = Math.sqrt(sumL2 / n);
            double rmsR = Math.sqrt(sumR2 / n);
            double align = phaseAlignAccum / phaseAlignN;
            System.out.printf("  诊断: cos(2φ−38k)均值=%.3f (1=对齐 0=正交)%n", align);
            check("立体声 PLL 锁定", dec.lockSmooth > 0.5f, "lock=" + dec.lockSmooth);
            check("立体声 L/R 分离", rmsL > rmsR * 2, "rmsL=" + rmsL + " rmsR=" + rmsR);
        }
        System.out.println(fails == 0 ? "\nALL PASS" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
