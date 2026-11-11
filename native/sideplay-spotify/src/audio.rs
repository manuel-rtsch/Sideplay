//! PCM sink. Decoded S16LE stereo is queued for the Kotlin AudioTrack drainer.
//!
//! The bounded channel is deliberate: the consumer (AudioTrack) is the real-time clock. When
//! Kotlin stops draining, `send` blocks the librespot player thread, which is what keeps the
//! decode position tied to playback instead of racing through the track. Kotlin must keep
//! draining until the player has processed a pause/stop command; otherwise the player thread
//! cannot process that command.

use librespot_playback::audio_backend::{Open, Sink, SinkResult};
use librespot_playback::config::AudioFormat;
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use std::sync::mpsc::SyncSender;

pub const CHANNELS: u16 = 2;
pub const SAMPLE_RATE: u32 = 44_100;

pub struct PcmSink {
    tx: SyncSender<Vec<u8>>,
    writes: u64,
}

impl PcmSink {
    pub fn new(tx: SyncSender<Vec<u8>>) -> Self {
        Self { tx, writes: 0 }
    }
}

impl Open for PcmSink {
    fn open(_device: Option<String>, _format: AudioFormat) -> Self {
        unreachable!("PcmSink is constructed with its channel")
    }
}

impl Sink for PcmSink {
    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        let pcm = match packet {
            AudioPacket::Samples(samples) => {
                let s16 = converter.f64_to_s16(&samples);
                let mut bytes = Vec::with_capacity(s16.len() * 2);
                for sample in s16 {
                    bytes.extend_from_slice(&sample.to_le_bytes());
                }
                bytes
            }
            AudioPacket::Raw(bytes) => bytes,
        };
        let bytes = pcm.len();
        if self.writes == 0 {
            log::info!("pcm first write bytes={bytes}");
        }
        // Blocking send is the pacing mechanism: the Kotlin drainer writes to AudioTrack with
        // WRITE_BLOCKING, so the hardware clock controls how fast this channel is consumed.
        // The player thread is blocked here only while the consumer is behind; it wakes as soon
        // as one buffer is drained, which is also when it can process a queued pause/play/load.
        match self.tx.send(pcm) {
            Ok(()) => {
                self.writes += 1;
                if self.writes % 100 == 0 {
                    log::info!("pcm writes={} last_bytes={bytes}", self.writes);
                }
            }
            Err(_) => log::warn!("pcm send failed: receiver disconnected"),
        }
        Ok(())
    }
}
