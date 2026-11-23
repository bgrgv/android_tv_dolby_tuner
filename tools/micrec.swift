// micrec: record the default macOS input for N seconds to a float32 WAV at the device's native rate.
// ffmpeg's avfoundation capture dropped about half of the buffers on a MacBook Pro mic, which ruins
// sweep deconvolution; AVAudioEngine does not.
//
//   swiftc -O tools/micrec.swift -o tools/micrec
//   tools/micrec out.wav 15        # prints "rate <Hz> frames <n>"
import AVFoundation

let args = CommandLine.arguments
guard args.count >= 3, let secs = Double(args[2]) else {
    FileHandle.standardError.write("usage: micrec OUT.wav SECONDS\n".data(using: .utf8)!)
    exit(2)
}
let engine = AVAudioEngine()
let input = engine.inputNode
let fmt = input.outputFormat(forBus: 0)
let settings: [String: Any] = [
    AVFormatIDKey: kAudioFormatLinearPCM,
    AVSampleRateKey: fmt.sampleRate,
    AVNumberOfChannelsKey: fmt.channelCount,
    AVLinearPCMBitDepthKey: 32,
    AVLinearPCMIsFloatKey: true,
]
var file: AVAudioFile? = try AVAudioFile(forWriting: URL(fileURLWithPath: args[1]), settings: settings,
                           commonFormat: .pcmFormatFloat32, interleaved: false)
var frames: AVAudioFramePosition = 0
let lock = NSLock()
input.installTap(onBus: 0, bufferSize: 4096, format: fmt) { buf, _ in
    lock.lock(); defer { lock.unlock() }
    try? file?.write(from: buf)
    frames += AVAudioFramePosition(buf.frameLength)
}
try engine.start()
Thread.sleep(forTimeInterval: secs)
engine.stop()
input.removeTap(onBus: 0)
lock.lock(); file = nil; lock.unlock()  // deinit finalises the WAV header
print("rate \(Int(fmt.sampleRate)) frames \(frames)")
