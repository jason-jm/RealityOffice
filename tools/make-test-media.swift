// Writes the test clips the instrumentation tests play (macOS, no other tools):
//   swift tools/make-test-media.swift <out dir> [<soundtrack.m4a>]
// clip.mp4    — 8 s of H.264 at 640×360, 30 fps: a square crossing a background that changes colour each
//               second (a tick more along its foot each second), with the soundtrack given (a video's sound is what keeps a player's clock: on the
//               PICO emulator a video without one reports no position while it plays)
// poster.png  — its first frame
// The sounds are made by tools/make-test-media.sh with afconvert. (macOS 27 deprecates the AVFoundation
// calls used here for newer ones; these still run, on older systems too.)
import AVFoundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
let w = 640, h = 360, fps = 30, seconds = 8
let backgrounds: [(CGFloat, CGFloat, CGFloat)] = [(0.12, 0.31, 0.47), (0.13, 0.45, 0.27), (0.44, 0.19, 0.63), (0.77, 0.24, 0.11),
                                                   (0.0, 0.44, 0.45), (0.55, 0.42, 0.0), (0.6, 0.15, 0.35), (0.25, 0.25, 0.3)]

func draw(_ ctx: CGContext, frame i: Int) {
    let (r, g, b) = backgrounds[min(i / fps, backgrounds.count - 1)]
    ctx.setFillColor(CGColor(red: r, green: g, blue: b, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
    let x = CGFloat(i) / CGFloat(fps * seconds - 1) * CGFloat(w - 120) + 20
    ctx.setFillColor(CGColor(red: 1, green: 0.82, blue: 0.4, alpha: 1))
    ctx.fill(CGRect(x: x, y: CGFloat(h) / 2 - 40, width: 80, height: 80))
    // A tick per second along the foot, so a frame tells how far the clip has played.
    ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.9))
    for s in 0...(i / fps) { ctx.fill(CGRect(x: 20 + s * 30, y: 20, width: 20, height: 20)) }
}

let soundtrack = CommandLine.arguments.count > 2 ? URL(fileURLWithPath: CommandLine.arguments[2]) : nil
let clip = out.appendingPathComponent("clip.mp4")
let silent = out.appendingPathComponent(soundtrack == nil ? "clip.mp4" : "clip-video.mp4")
try? FileManager.default.removeItem(at: silent)
let writer = try AVAssetWriter(outputURL: silent, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: w, AVVideoHeightKey: h,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 120_000, AVVideoProfileLevelKey: AVVideoProfileLevelH264BaselineAutoLevel],
])
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB, kCVPixelBufferWidthKey as String: w, kCVPixelBufferHeightKey as String: h,
])
writer.add(input)
writer.startWriting()
writer.startSession(atSourceTime: .zero)
for i in 0..<(fps * seconds) {
    while !input.isReadyForMoreMediaData { usleep(1000) }
    var buffer: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
    let pb = buffer!
    CVPixelBufferLockBaseAddress(pb, [])
    let ctx = CGContext(data: CVPixelBufferGetBaseAddress(pb), width: w, height: h, bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pb),
                        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue)!
    draw(ctx, frame: i)
    CVPixelBufferUnlockBaseAddress(pb, [])
    adaptor.append(pb, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: CMTimeScale(fps)))
}
input.markAsFinished()
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
guard writer.status == .completed else { fatalError("clip.mp4: \(String(describing: writer.error))") }

// The soundtrack laid under the video (as long as the video), the two copied as they are into clip.mp4.
if let soundtrack {
    let video = AVURLAsset(url: silent), sound = AVURLAsset(url: soundtrack)
    let length = video.duration
    let comp = AVMutableComposition()
    try comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)!
        .insertTimeRange(CMTimeRange(start: .zero, duration: length), of: video.tracks(withMediaType: .video)[0], at: .zero)
    try comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
        .insertTimeRange(CMTimeRange(start: .zero, duration: CMTimeMinimum(length, sound.duration)), of: sound.tracks(withMediaType: .audio)[0], at: .zero)
    try? FileManager.default.removeItem(at: clip)
    let export = AVAssetExportSession(asset: comp, presetName: AVAssetExportPresetPassthrough)!
    export.outputURL = clip
    export.outputFileType = .mp4
    let exported = DispatchSemaphore(value: 0)
    export.exportAsynchronously { exported.signal() }
    exported.wait()
    guard export.status == .completed else { fatalError("clip.mp4 with sound: \(String(describing: export.error))") }
    try FileManager.default.removeItem(at: silent)
}

// The poster: the first frame as a PNG.
let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
draw(ctx, frame: 0)
let poster = out.appendingPathComponent("poster.png")
let dest = CGImageDestinationCreateWithURL(poster as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
CGImageDestinationFinalize(dest)
print("clip.mp4, poster.png → \(out.path)")
