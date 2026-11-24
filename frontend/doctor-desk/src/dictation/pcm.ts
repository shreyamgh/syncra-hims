import { SAMPLE_RATE } from './protocol';

/**
 * Converts microphone audio (Float32 at the device rate, usually 44.1/48 kHz)
 * into 16 kHz little-endian PCM16 for the dictation socket. Each output sample
 * averages the input samples it covers (a box filter), which suppresses
 * aliasing well enough for speech; leftover samples carry over between chunks.
 */
export class Pcm16Resampler {
  private readonly ratio: number;
  private carry: Float32Array = new Float32Array(0);

  constructor(inputRate: number, outputRate = SAMPLE_RATE) {
    if (inputRate < outputRate) throw new Error(`Input rate ${inputRate} is below ${outputRate} Hz`);
    this.ratio = inputRate / outputRate;
  }

  process(chunk: Float32Array): Int16Array {
    const data = new Float32Array(this.carry.length + chunk.length);
    data.set(this.carry);
    data.set(chunk, this.carry.length);

    const outLength = Math.floor(data.length / this.ratio);
    const out = new Int16Array(outLength);
    for (let i = 0; i < outLength; i++) {
      const start = Math.floor(i * this.ratio);
      const end = Math.max(start + 1, Math.floor((i + 1) * this.ratio));
      let sum = 0;
      for (let j = start; j < end; j++) sum += data[j]!;
      const sample = Math.max(-1, Math.min(1, sum / (end - start)));
      out[i] = Math.round(sample < 0 ? sample * 0x8000 : sample * 0x7fff);
    }
    this.carry = data.slice(Math.floor(outLength * this.ratio));
    return out;
  }
}

/** Root-mean-square level (0..1) for the input meter. */
export function rms(samples: Float32Array): number {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (const s of samples) sum += s * s;
  return Math.sqrt(sum / samples.length);
}
