# LinkedIn Post

I got annoyed by Amazon ads. So I told an AI to reverse-engineer Amazon's own app — and build me a replacement without the ads.

Mid-call, my living room TV turned on by itself. 🤔

The AI had finished the PoC, pushed it to my FireTV via ADB, and was watching my TV via debug screenshots to verify its own homework.

No ads. Half a day. ~$30.

Here's what happened. 👇

---

I pay €8,99/month for Prime. Amazon wants €2,99 extra to watch without ads. I ruled it a side project.

The key insight: ads are injected by the client app, not the stream. Replace the app → no ads.

So I gave an AI the FireTV APK binary and said: figure out how the ads work.

It decompiled the bytecode, mapped every ad class, and produced a 13-row patch strategy table — preroll, midroll, difficulty, breakage risk. All of it. No complaints.

Patching hit a wall (needs root, I have no root). New plan: vibe code a replacement.

The AI reverse-engineered the Kodi plugin's Python source, extracted every API endpoint and Widevine DRM handshake, and had a working PoC in half a day — while I was in standups.

Then it pushed the APK to my FireTV, launched it, and took screenshots via `adb shell screencap` to verify the video was actually playing.

That's when my TV turned on. 📺

---

Total cost: ~$500 in AI subscriptions. PoC in half a day. Full app in a few days. Fancy UI took longer — AI builds fast, but still needs a human to say "that button is in the wrong place."

---

What I learned:

→ AI reverse engineers well. Give it bytecode, it maps it. No coffee required.
→ 80% solution is fast. Last 20% is where the money goes.
→ Claude Sonnet doesn't give up. Codex is faster but asks permission. Pick your fighter.
→ Let AIs review each other — less defensive than humans.
→ Prompting is a skill. "Fix the UI" → nothing. "Crashes when scrolling past 20 items on the remote" → fixed.
→ Sandbox your AI. Mine turned on my TV during a quarterly OKR call.

---

Total ads watched since: **0**
Times TV turned on uninvited: **1** (acceptable)

👉 github.com/ScriptGod1337/amazon-vod-noads
👉 github.com/ScriptGod1337/amazon-vod-fireos

#AI #VibeCoding #AndroidDev #Claude #ClaudeCode #OpenAI #SideProject #FireTV #AmazonPrime
