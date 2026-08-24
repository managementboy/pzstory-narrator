--[[
  PZStory - Phase 2 harness.

  F9  - dump the provider-facing live-state projection to console
  F10 - fire a tiny model request and stream the reply into console

  There is still no UI. The point of this file is to prove the whole chain
  end to end - key, network, SSE parsing, background thread, per-frame drain -
  before any of it is hidden behind a window.

      grep -a 'PZStoryStream' console.txt
]]

local TAG = "[PZStoryProbe] "
local SNAPSHOT_BIND = "PZStory: provider state to log"
local TEST_BIND     = "PZStory: model self-test"

local function say(...)
    local parts = {}
    for i = 1, select("#", ...) do parts[i] = tostring(select(i, ...)) end
    print(TAG .. table.concat(parts, " "))
end

-- Anything the player triggers must acknowledge itself on screen, or a working
-- keybind is indistinguishable from a broken one.
local function notify(text, good)
    local pl = getPlayer()
    if pl == nil then return end
    pcall(function()
        if good then HaloTextHelper.addGoodText(pl, text)
        else HaloTextHelper.addBadText(pl, text) end
    end)
end

table.insert(keyBinding, { value = SNAPSHOT_BIND, key = Keyboard.KEY_F9 })
table.insert(keyBinding, { value = TEST_BIND,     key = Keyboard.KEY_F10 })

-- ---------------------------------------------------------------- snapshot

local function takeSnapshot(why)
    if PZStory == nil then
        say("cannot snapshot - PZStory table is nil")
        notify("PZStory: bridge not loaded", false)
        return
    end
    -- Print the same minimised live-state block used in a provider request,
    -- not the raw local snapshot with exact coordinates and diagnostics. It is
    -- still private (name, inventory, wounds), so this stays an explicit probe.
    local snapshot
    local ok, err = pcall(function() snapshot = PZStory.providerPreview() end)
    if ok then
        print("[PZStoryProviderState] " .. tostring(snapshot or ""))
        say("provider state written to console (" .. why .. ")")
        notify("PZStory: provider state written", true)
    else
        say("provider state FAILED (" .. why .. "): " .. tostring(err))
        notify("PZStory: provider state failed", false)
    end
end

-- ------------------------------------------------------------- model test

-- Drain state. The Java side runs the request on its own thread; all we do
-- here is pull whatever has arrived, once per tick, and never block.
local stream = {
    running = false,
    text    = "",
    ticks   = 0,      -- ticks spent waiting
    chunks  = 0,      -- polls that actually returned text
}

local function onTick()
    if not stream.running then return end
    stream.ticks = stream.ticks + 1

    local raw, data
    local ok = pcall(function()
        raw = PZStory.pollStream()
        data = PZStoryJSONDecode(raw)
    end)
    if not ok or raw == nil or type(data) ~= "table" then
        stream.running = false
        say("poll failed - giving up")
        return
    end

    local delta = data.delta
    local done  = data.done == true
    local err   = data.error

    if delta and delta ~= "" then
        stream.chunks = stream.chunks + 1
        stream.text = stream.text .. delta
    end

    if done then
        stream.running = false
        if err then
            print("[PZStoryStream] FAILED: " .. tostring(err))
            notify("PZStory: request failed", false)
        else
            -- One line per chunk would bury the log; the useful evidence is
            -- that text arrived in many pieces rather than one lump.
            print("[PZStoryStream] done after " .. stream.ticks .. " ticks in "
                  .. stream.chunks .. " chunks, " .. #stream.text .. " chars")
            print("[PZStoryStream] status: " .. tostring(raw))
            print("[PZStoryStream] text: " .. stream.text:gsub("\n", " / "))
            notify("PZStory: " .. #stream.text .. " chars in " .. stream.chunks .. " chunks", true)
        end
    end
end

local function runSelfTest()
    if PZStory == nil then
        notify("PZStory: bridge not loaded", false)
        return
    end
    if stream.running then
        say("a request is already running")
        notify("PZStory: already running", false)
        return
    end

    stream.text, stream.ticks, stream.chunks = "", 0, 0

    local refusal
    local ok = pcall(function() refusal = PZStory.selfTest() end)
    if not ok then
        say("selfTest threw")
        notify("PZStory: self-test threw", false)
        return
    end
    if refusal ~= nil then
        say("refused: " .. tostring(refusal))
        notify("PZStory: " .. tostring(refusal), false)
        return
    end

    stream.running = true
    say("request started, streaming...")
    notify("PZStory: asking the model...", true)
end

-- ------------------------------------------------------------------ wiring

Events.OnGameStart.Add(function()
    say("bridge check: PZStory=", type(PZStory),
        " version=", PZStory and PZStory.version() or "n/a")
    -- Report profile state at load, so a bad profiles.json is visible before
    -- the player spends a keypress on it.
    if PZStory ~= nil then
        pcall(function() say("config: " .. tostring(PZStory.reloadConfig())) end)
        pcall(function() say("profiles: " .. tostring(PZStory.profiles())) end)
    end
    takeSnapshot("OnGameStart")
end)

Events.OnKeyPressed.Add(function(key)
    if key == getCore():getKey(SNAPSHOT_BIND) then takeSnapshot("keypress") end
    if key == getCore():getKey(TEST_BIND) then runSelfTest() end
end)

Events.OnTick.Add(onTick)
