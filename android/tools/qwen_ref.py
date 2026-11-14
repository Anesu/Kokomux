"""A greedy Qwen3-TTS 0.6B Base voice clone, as kokomux runs it: onnx-community's 4-bit export
(text_embed, talker_cache, the codec's encoder and decoder, speaker_encoder) with wavekat's 4-bit
cached code predictor and full-precision code embeddings (both exports of the same weights). It
mirrors onnx-community's inference.py (_generate_clone, _ar_loop_cached); the clip is encoded in 1 s
windows 12 frames apart (each window's last, half-padded frame dropped), and the frames decoded 25 at
a time, 10 of them context. Writes the codes as JSON, and the wav.
usage: qwen_ref.py MODELS WAVEKAT QWEN_DIR TOKENIZER_JSON CLIP.wav "clip words or -" "text" OUT_PREFIX [MAX]"""
import json, sys
import numpy as np, onnxruntime as ort, soundfile as sf
from tokenizers import Tokenizer

models, wk, qdir, tokjson, clip, ref_text, text, out = sys.argv[1:9]
cfg = json.load(open(f"{qdir}/config.json")); tc = cfg["talker_config"]
tok = Tokenizer.from_file(tokjson)
ids = lambda s: np.array([tok.encode(s, add_special_tokens=False).ids], np.int64)
so = ort.SessionOptions(); so.log_severity_level = 3
S = {n: ort.InferenceSession(f"{models}/{n}.onnx", so, providers=["CPUExecutionProvider"]) for n in
     ["text_embed", "talker_cache", "tok_decoder", "tok_encoder", "speaker_encoder"]}
S["code_predictor"] = ort.InferenceSession(f"{wk}/int4/code_predictor.onnx", so, providers=["CPUExecutionProvider"])
codec_table = np.load(f"{wk}/embeddings/talker_codec_embedding.npy")
cp_tables = [np.load(f"{wk}/embeddings/cp_codec_embedding_{g}.npy") for g in range(15)]
run = lambda n, feed: S[n].run(None, feed)
et = lambda x: run("text_embed", {"text_ids": np.asarray(x, np.int64)})[0]
ec = lambda x: codec_table[np.asarray(x, np.int64)]
step_embed = lambda c: np.stack([codec_table[f[0]] + sum(cp_tables[g][f[g + 1]] for g in range(15)) for f in np.asarray(c)])

wav, sr = sf.read(clip, dtype="float32"); assert sr == 24000
def encode(w):
    frames = -(-len(w) // 1920); out = []
    for s in range(0, len(w), 23040):
        c = w[s:s + 24000]; c = np.pad(c, (0, 24000 - len(c)))
        codes = run("tok_encoder", {"audio": c.reshape(1, 1, 24000)})[0][0]
        out.append(codes[:12])
    return np.concatenate(out)[:frames]
spk = run("speaker_encoder", {"audio": wav[None]})[0].reshape(1, 1, -1)
H = spk.shape[-1]
bos_e, eos_e, pad_e = np.split(et([[cfg["tts_bos_token_id"], cfg["tts_eos_token_id"], cfg["tts_pad_token_id"]]]), 3, axis=1)
lang = tc["codec_language_id"]["english"]
codec0 = ec([[tc["codec_think_id"], tc["codec_think_bos_id"], lang, tc["codec_think_eos_id"]]])
codec1 = ec([[tc["codec_pad_id"], tc["codec_bos_id"]]])
codec_input = np.concatenate([codec0, spk, codec1], axis=1)
input_id = ids(f"<|im_start|>assistant\n{text}<|im_end|>\n<|im_start|>assistant\n")
role = et(input_id[:, :3])
pad_block = np.concatenate([np.repeat(pad_e, codec_input.shape[1] - 2, axis=1), bos_e], axis=1)
base = np.concatenate([role, pad_block + codec_input[:, :-1]], axis=1)
text_id = input_id[:, 3:-5]
if ref_text != "-":
    ref_code = encode(wav)
    ref_id = ids(f"<|im_start|>assistant\n{ref_text}<|im_end|>\n")[:, 3:-2]
    te = np.concatenate([et(np.concatenate([ref_id, text_id], axis=1)), eos_e], axis=1)
    T1 = te.shape[1]
    cd = np.concatenate([ec([[tc["codec_bos_id"]]]), step_embed(ref_code)[None]], axis=1)
    icl = np.concatenate([te + ec([[tc["codec_pad_id"]] * T1]), cd + pad_e], axis=1)
    talker_in = np.concatenate([base, icl], axis=1)
else:
    ref_code = np.zeros((0, 16), np.int64)
    L = text_id.shape[1]
    block1 = np.concatenate([et(text_id), eos_e], axis=1) + ec([[tc["codec_pad_id"]] * (L + 1)])
    talker_in = np.concatenate([base, block1, pad_e + ec([[tc["codec_bos_id"]]])], axis=1)

vocab, eos = tc["vocab_size"], tc["codec_eos_token_id"]
suppress = np.array([i for i in range(vocab - 1024, vocab) if i != eos])
names = [i.name for i in S["talker_cache"].get_inputs()][3:]
def talk(x, pos, total, past):
    feed = {"inputs_embeds": x.astype(np.float32), "position_ids": pos, "attention_mask": np.ones((1, total), np.int64)}
    feed.update(dict(zip(names, past)))
    o = run("talker_cache", feed); return o[0], o[1], list(o[2:])
past = [np.zeros((1, 8, 0, 128), np.float32) for _ in names]
T0 = talker_in.shape[1]
logits, hidden, past = talk(talker_in, np.broadcast_to(np.arange(T0), (3, 1, T0)).copy(), T0, past)
total, codes, prev = T0, [], []
for step in range(int(sys.argv[9]) if len(sys.argv) > 9 else 400):
    first = logits[0, -1].astype(np.float64).copy(); first[suppress] = -np.inf
    if step < 2: first[eos] = -np.inf
    if prev:
        i = np.array(sorted(set(prev))); sc = first[i]; first[i] = np.where(sc < 0, sc * 1.05, sc / 1.05)
    c0 = int(np.argmax(first))
    if c0 == eos: break
    prev.append(c0)
    c16 = np.zeros((1, 16), np.int64); c16[0, 0] = c0
    x = np.stack([hidden[0, -1], codec_table[c0]])[None].astype(np.float32)
    pk = np.zeros((5, 1, 8, 0, 128), np.float32); pv = pk
    for g in range(15):
        o = run("code_predictor", {"inputs_embeds": x, "generation_steps": np.array([g], np.int64), "past_keys": pk, "past_values": pv})
        pk, pv = o[1], o[2]
        c16[0, g + 1] = int(np.argmax(o[0][0, -1]))
        x = cp_tables[g][c16[0, g + 1]].reshape(1, 1, -1).astype(np.float32)
    codes.append(c16[0].tolist())
    nxt = step_embed(c16)[:, None] + pad_e[:, 0][:, None]
    logits, hidden, past = talk(nxt, np.full((3, 1, 1), total, np.int64), total + 1, past); total += 1
codes = np.array(codes, np.int64)
# Decode in 25-frame windows: 10 frames of what came before, then 15 new.
pcm = []
for s in range(0, len(codes), 15):
    lo = max(0, s - 10); win = codes[lo:s + 15]; n = len(win)
    win = win[np.arange(25) % n] if n < 25 else win
    w = run("tok_decoder", {"audio_codes": win[None]})[0].reshape(-1)
    pcm.append(w[(s - lo) * 1920:(min(s + 15, len(codes)) - lo) * 1920])
pcm = np.concatenate(pcm) if pcm else np.zeros(0, np.float32)
sf.write(out + ".wav", pcm, 24000)
json.dump({"prefill": T0, "ref_frames": len(ref_code), "ref_code_head": ref_code[:3].tolist(), "spk_head": spk[0, 0, :4].tolist(),
           "codes": codes.tolist(), "samples": len(pcm), "pcm_head": pcm[24000:24008].tolist()}, open(out + ".json", "w"))
print("prefill", T0, "ref frames", len(ref_code), "frames", len(codes), "seconds", len(pcm) / 24000)
