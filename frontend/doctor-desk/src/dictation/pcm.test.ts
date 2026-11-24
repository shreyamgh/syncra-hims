import { describe, expect, it } from 'vitest';
import { Pcm16Resampler, rms } from './pcm';

describe('Pcm16Resampler', () => {
  it('converts 48 kHz float audio to 16 kHz PCM16, carrying samples across chunks', () => {
    const resampler = new Pcm16Resampler(48_000);
    const second = new Float32Array(48_000).fill(0.5);
    let total = 0;
    for (let i = 0; i < second.length; i += 4096) total += resampler.process(second.subarray(i, i + 4096)).length;
    expect(total).toBe(16_000);
  });

  it('averages and clamps samples to the PCM16 range', () => {
    const out = new Pcm16Resampler(48_000).process(Float32Array.from([1, 1, 1, -2, -2, -2, 0.5, 0.5, 0.5]));
    expect(Array.from(out)).toEqual([32767, -32768, 16384]);
  });

  it('handles non-integer ratios (44.1 kHz)', () => {
    const resampler = new Pcm16Resampler(44_100);
    expect(resampler.process(new Float32Array(44_100)).length).toBe(16_000);
  });

  it('measures the input level', () => {
    expect(rms(Float32Array.from([0.5, -0.5]))).toBeCloseTo(0.5);
    expect(rms(new Float32Array())).toBe(0);
  });
});
