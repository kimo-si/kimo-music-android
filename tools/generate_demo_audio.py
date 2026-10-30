"""生成 Kimo 自有的纯合成演示音频；需要 numpy 与 ffmpeg，不引用任何第三方录音。"""
from pathlib import Path
import subprocess
import tempfile
import wave
import numpy as np

SAMPLE_RATE = 22050
OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/assets'
PIECES = (
    ('evening_wind', 252, (220.0, 174.614, 261.626, 195.998), 7),
    ('mountain_letter', 198, (196.0, 261.626, 164.814, 220.0), 13),
    ('slow_afternoon', 216, (174.614, 220.0, 146.832, 196.0), 29),
)


# 叠加柔和正弦和声与短音，生成没有人声的原创环境音演示片段。
def chord_block(root: float, length: int, seed: int) -> np.ndarray:
    time = np.arange(length, dtype=np.float64) / SAMPLE_RATE
    envelope = np.sin(np.pi * time / (length / SAMPLE_RATE)) ** 1.2
    block = np.zeros(length, dtype=np.float64)
    for ratio in (1, 1.5, 2, 2.5, 3):
        block += np.sin(2 * np.pi * root * ratio * time) * envelope * 0.045
    generator = np.random.default_rng(seed)
    for offset in (0.4, 2.2, 4.6, 6.4):
        elapsed = np.maximum(0, time - offset)
        frequency = root * generator.choice((2, 2.5, 3, 4))
        gate = (time >= offset) * (1 - np.exp(-elapsed * 25)) * np.exp(-elapsed * 1.4)
        block += np.sin(2 * np.pi * frequency * elapsed) * gate * 0.08
    return block.astype(np.float32)


# 按明确的和弦循环合成指定时长，并在首尾添加平滑淡入淡出。
def synthesize(duration: int, chords: tuple[float, ...], seed: int) -> np.ndarray:
    audio = np.zeros(duration * SAMPLE_RATE, dtype=np.float32)
    block_size = SAMPLE_RATE * 8
    for index, start in enumerate(range(0, len(audio), block_size)):
        length = min(block_size, len(audio) - start)
        audio[start:start + length] = chord_block(chords[index % len(chords)], length, seed + index)
    fade_length = SAMPLE_RATE * 3
    audio[:fade_length] *= np.linspace(0, 1, fade_length)
    audio[-fade_length:] *= np.linspace(1, 0, fade_length)
    return (np.clip(audio, -0.95, 0.95) * 32767).astype('<i2')


# 临时 WAV 转为标准 MP3，应用安装后不依赖网络即可验证播放服务。
def render(name: str, duration: int, chords: tuple[float, ...], seed: int) -> None:
    with tempfile.TemporaryDirectory(prefix='kimo-audio-') as directory:
        source = Path(directory) / f'{name}.wav'
        with wave.open(str(source), 'wb') as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(SAMPLE_RATE)
            output.writeframes(synthesize(duration, chords, seed).tobytes())
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i', str(source),
                        '-codec:a', 'libmp3lame', '-b:a', '64k', str(OUTPUT / f'{name}.mp3')], check=True)


# 在显式执行脚本时重建全部演示音频。
def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for piece in PIECES:
        render(*piece)
    print('已生成 3 首原创合成演示曲')


if __name__ == '__main__':
    main()
