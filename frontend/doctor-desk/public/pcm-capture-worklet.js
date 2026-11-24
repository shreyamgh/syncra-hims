// AudioWorklet: forwards raw mono microphone samples to the main thread in
// ~85 ms batches. Resampling to 16 kHz PCM16 happens in src/dictation/pcm.ts.
class PcmCaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buffer = new Float32Array(4096);
    this.length = 0;
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (channel) {
      for (let i = 0; i < channel.length; i++) {
        this.buffer[this.length++] = channel[i];
        if (this.length === this.buffer.length) {
          this.port.postMessage(this.buffer.slice(0));
          this.length = 0;
        }
      }
    }
    return true;
  }
}

registerProcessor('pcm-capture', PcmCaptureProcessor);
