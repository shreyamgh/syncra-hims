import { Pcm16Resampler, rms } from './pcm';

export interface MicrophoneCapture {
  stop(): Promise<void>;
}

export type PcmListener = (pcm: Int16Array, level: number) => void;

/**
 * Opens the microphone and streams 16 kHz PCM16 chunks to {@code onPcm}.
 * Requires a secure context (HTTPS, or localhost in development).
 */
export async function startMicrophone(onPcm: PcmListener): Promise<MicrophoneCapture> {
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error('Microphone access needs a secure (HTTPS) connection.');
  }
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
  });
  const context = new AudioContext();
  try {
    // Created after the server's "ready" message (not inside the click handler), so
    // the browser may start it suspended; the click on the mic button allows resuming.
    await context.resume();
    await context.audioWorklet.addModule('/pcm-capture-worklet.js');
  } catch (error) {
    stream.getTracks().forEach((t) => t.stop());
    await context.close();
    throw error;
  }
  const source = context.createMediaStreamSource(stream);
  const node = new AudioWorkletNode(context, 'pcm-capture', { numberOfInputs: 1, numberOfOutputs: 1 });
  const mute = context.createGain();
  mute.gain.value = 0; // keeps the node in the rendered graph without echoing the mic
  const resampler = new Pcm16Resampler(context.sampleRate);

  node.port.onmessage = (event: MessageEvent<Float32Array>) => {
    onPcm(resampler.process(event.data), rms(event.data));
  };
  source.connect(node);
  node.connect(mute).connect(context.destination);

  return {
    async stop() {
      node.port.onmessage = null;
      source.disconnect();
      node.disconnect();
      stream.getTracks().forEach((t) => t.stop());
      await context.close();
    },
  };
}
