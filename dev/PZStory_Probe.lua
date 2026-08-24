--[[
  PZStory - Phase 2 harness.

  F8  - toggle the local Testing Mode overlay
  F5  - cycle Pending, History, Facts, Threads and Continuity Evidence
  F9  - dump the provider-facing live-state projection to console
  F10 - fire a tiny model request and stream the reply into console
  F11 - dump the local event journal (contains local ids)
  F6  - dump structured place memory (contains local ids)
  F4  - open the debug-only guided Test Lab
  F3  - run/advance the selected Test Lab suite

  There is still no UI. The point of this file is to prove the whole chain
  end to end - key, network, SSE parsing, background thread, per-frame drain -
  before any of it is hidden behind a window.

      grep -a 'PZStoryStream' console.txt
]]

require "ISUI/ISPanel"
require "ISUI/ISButton"

local TAG = "[PZStoryProbe] "
local OVERLAY_BIND  = "PZStory: testing mode overlay"
local INBOX_BIND    = "PZStory: testing mode inbox view"
local SNAPSHOT_BIND = "PZStory: provider state to log"
local TEST_BIND     = "PZStory: model self-test"
local EVENTS_BIND   = "PZStory: local event journal to log"
local MEMORY_BIND   = "PZStory: local world memory to log"
local LAB_BIND      = "PZStory: open guided test lab"
local LAB_RUN_BIND  = "PZStory: run guided test step"

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

table.insert(keyBinding, { value = OVERLAY_BIND,  key = Keyboard.KEY_F8 })
table.insert(keyBinding, { value = INBOX_BIND,    key = Keyboard.KEY_F5 })
table.insert(keyBinding, { value = SNAPSHOT_BIND, key = Keyboard.KEY_F9 })
table.insert(keyBinding, { value = TEST_BIND,     key = Keyboard.KEY_F10 })
table.insert(keyBinding, { value = EVENTS_BIND,   key = Keyboard.KEY_F11 })
table.insert(keyBinding, { value = MEMORY_BIND,   key = Keyboard.KEY_F6 })
table.insert(keyBinding, { value = LAB_BIND,      key = Keyboard.KEY_F4 })
table.insert(keyBinding, { value = LAB_RUN_BIND,  key = Keyboard.KEY_F3 })

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

-- ---------------------------------------------------------- 2.0 local data

local function dumpLocal(method, tag, label)
    if PZStory == nil or type(PZStory[method]) ~= "function" then
        notify("PZStory: 2.0 bridge not loaded", false)
        return
    end
    local value
    local ok = pcall(function() value = PZStory[method]() end)
    if not ok then
        notify("PZStory: could not read " .. label, false)
        return
    end
    -- Unlike F9, these diagnostics intentionally contain stable LOCAL ids.
    -- They are never part of a provider request. Treat the console as private.
    print("[" .. tag .. "] " .. tostring(value or ""))
    notify("PZStory: " .. label .. " written", true)
end

-- ------------------------------------------------------- Testing Mode UI

-- This overlay is deliberately implemented in the local probe, not in the
-- production device. It reads the same on-disk diagnostics as F11/F6, never
-- starts a provider request, and never displays raw room ids or coordinates.
local overlay = {
    visible = false,
    nextRefresh = 0,
    place = nil,
    events = {},
    facts = {},
    threads = {},
    continuity = {},
    pending = 0,
    error = nil,
    mode = "pending",
}

local function decodeDiagnostic(method)
    if PZStory == nil or type(PZStory[method]) ~= "function" then return nil end
    local value, decoded
    local ok = pcall(function()
        value = PZStory[method]()
        decoded = PZStoryJSONDecode(value)
    end)
    if not ok or type(decoded) ~= "table" then return nil end
    return decoded
end

local function refreshOverlay()
    if not overlay.visible then return end
    local now = getTimestampMs()
    if now < overlay.nextRefresh then return end
    overlay.nextRefresh = now + 500

    local memory = decodeDiagnostic("worldMemory")
    local journal = decodeDiagnostic("eventJournal")
    local factRoot = decodeDiagnostic("factMemory")
    local threadRoot = decodeDiagnostic("threadMemory")
    local continuityRoot = decodeDiagnostic("continuityMemory")
    if memory == nil or journal == nil or factRoot == nil or threadRoot == nil
            or continuityRoot == nil then
        overlay.error = "diagnostics unavailable"
        return
    end

    overlay.error = nil
    overlay.place = nil
    if type(memory.places) == "table" then
        for _, place in ipairs(memory.places) do
            if place.id == memory.currentPlaceId then
                overlay.place = {
                    label = tostring(place.label or "unknown place"),
                    visits = tonumber(place.visits) or 1,
                }
                break
            end
        end
    end

    overlay.pending = tonumber(journal.pending) or 0
    overlay.events = {}
    overlay.facts = {}
    overlay.threads = {}
    overlay.continuity = {}
    if type(journal.events) == "table" then
        for i = #journal.events, 1, -1 do
            local event = journal.events[i]
            local narrated = type(event) == "table" and tonumber(event.narratedPage or 0) or 0
            if type(event) == "table"
                    and (overlay.mode == "recent" or narrated == 0) then
                table.insert(overlay.events, {
                    kind = tostring(event.type or "event"):gsub("_", " "):upper(),
                    importance = tonumber(event.importance) or 0,
                    stamp = tostring(event.stamp or ""),
                    summary = tostring(event.summary or ""),
                    narrated = narrated,
                })
                if #overlay.events >= 6 then break end
            end
        end
    end
    local continuityMemory = continuityRoot.continuityMemory
    if type(continuityMemory) == "table" and type(continuityMemory.entries) == "table" then
        for i = #continuityMemory.entries, 1, -1 do
            local entry = continuityMemory.entries[i]
            if type(entry) == "table" then
                table.insert(overlay.continuity, {
                    kind = tostring(entry.kind or "evidence"):upper(),
                    label = tostring(entry.label or ""),
                    occurrences = tonumber(entry.occurrences) or 0,
                })
                if #overlay.continuity >= 8 then break end
            end
        end
    end
    local threadMemory = threadRoot.threadMemory
    if type(threadMemory) == "table" and type(threadMemory.threads) == "table" then
        for i = #threadMemory.threads, 1, -1 do
            local thread = threadMemory.threads[i]
            if type(thread) == "table" then
                table.insert(overlay.threads, {
                    key = tostring(thread.key or "thread"),
                    status = tostring(thread.status or "open"):upper(),
                    setup = tostring(thread.setup or ""),
                    resolution = tostring(thread.resolution or ""),
                })
                if #overlay.threads >= 6 then break end
            end
        end
    end
    local factMemory = factRoot.factMemory
    if type(factMemory) == "table" and type(factMemory.facts) == "table" then
        for i = #factMemory.facts, 1, -1 do
            local fact = factMemory.facts[i]
            if type(fact) == "table" then
                table.insert(overlay.facts, {
                    kind = tostring(fact.type or "knowledge"):upper(),
                    source = tostring(fact.source or "unknown"):upper(),
                    confidence = tonumber(fact.confidence) or 0,
                    text = tostring(fact.text or ""),
                    superseded = tonumber(fact.supersededBy or 0) > 0,
                })
                if #overlay.facts >= 6 then break end
            end
        end
    end
end

local function shadowText(font, x, y, text, r, g, b, centred)
    local tm = getTextManager()
    if centred then
        tm:DrawStringCentre(font, x + 1, y + 1, text, 0, 0, 0, 0.85)
        tm:DrawStringCentre(font, x, y, text, r, g, b, 1)
    else
        tm:DrawString(font, x + 1, y + 1, text, 0, 0, 0, 0.85)
        tm:DrawString(font, x, y, text, r, g, b, 1)
    end
end

local function drawOverlay()
    if not overlay.visible then return end
    local pl = getPlayer()
    if pl == nil then return end

    -- Anchor the place label in the world above the survivor's current room.
    -- It therefore moves naturally with the camera and sits over the garage
    -- while the survivor is standing inside it.
    local sx = IsoUtils.XToScreen(pl:getX(), pl:getY(), pl:getZ(), 0) - getCameraOffX()
    local sy = IsoUtils.YToScreen(pl:getX(), pl:getY(), pl:getZ(), 0) - getCameraOffY() - 105
    if overlay.place ~= nil then
        local visits = overlay.place.visits
        local status = visits > 1 and "CONFIRMED RETURN" or "NEW PLACE"
        local r, g, b = visits > 1 and 0.35 or 0.35, visits > 1 and 1.0 or 0.75, 0.45
        shadowText(UIFont.Medium, sx, sy, overlay.place.label:upper(), r, g, b, true)
        shadowText(UIFont.Small, sx, sy + 20,
            "VISIT " .. visits .. "  |  " .. status, r, g, b, true)
    else
        shadowText(UIFont.Small, sx, sy, "PLACE NOT YET OBSERVED", 1, 0.75, 0.2, true)
    end

    -- A compact screen-space inbox makes fast events visible even when their
    -- world position is already behind the player.
    local playerNum = pl:getPlayerNum()
    local x = getPlayerScreenLeft(playerNum) + 24
    local y = getPlayerScreenTop(playerNum) + 70
    shadowText(UIFont.Medium, x, y, "PZSTORY TESTING MODE", 0.45, 0.9, 1, false)
    y = y + 23
    if overlay.error ~= nil then
        shadowText(UIFont.Small, x, y, overlay.error, 1, 0.3, 0.3, false)
        return
    end
    local view = overlay.mode == "recent" and "RECENT HISTORY"
        or (overlay.mode == "facts" and "STORY FACTS"
        or (overlay.mode == "threads" and "STORY THREADS"
        or (overlay.mode == "continuity" and "CONTINUITY EVIDENCE" or "PENDING INBOX")))
    shadowText(UIFont.Small, x, y, view .. "  |  PENDING: " .. overlay.pending,
        overlay.pending > 0 and 1 or 0.6, overlay.pending > 0 and 0.8 or 0.9, 0.35, false)
    y = y + 18
    if overlay.mode == "continuity" then
        if #overlay.continuity == 0 then
            shadowText(UIFont.Small, x, y, "(no repeated evidence)", 0.7, 0.7, 0.7, false)
        else
            for _, entry in ipairs(overlay.continuity) do
                shadowText(UIFont.Small, x, y,
                    entry.kind .. "  OBSERVATIONS: " .. entry.occurrences,
                    0.45, 0.9, 1, false)
                y = y + 17
                local label = entry.label:gsub("[\r\n]", " ")
                if #label > 88 then label = label:sub(1, 85) .. "..." end
                shadowText(UIFont.Small, x + 14, y, label, 0.55, 0.8, 0.9, false)
                y = y + 17
            end
        end
    elseif overlay.mode == "threads" then
        if #overlay.threads == 0 then
            shadowText(UIFont.Small, x, y, "(no deliberate threads)", 0.7, 0.7, 0.7, false)
        else
            for _, thread in ipairs(overlay.threads) do
                local open = thread.status == "OPEN"
                local r, g, b = open and 1 or 0.55, open and 0.75 or 0.9, open and 0.3 or 0.65
                shadowText(UIFont.Small, x, y,
                    thread.key:upper() .. "  " .. thread.status, r, g, b, false)
                y = y + 17
                local detail = open and thread.setup or thread.resolution
                detail = detail:gsub("[\r\n]", " ")
                if #detail > 88 then detail = detail:sub(1, 85) .. "..." end
                shadowText(UIFont.Small, x + 14, y, detail, r, g, b, false)
                y = y + 17
            end
        end
    elseif overlay.mode == "facts" then
        if #overlay.facts == 0 then
            shadowText(UIFont.Small, x, y, "(no story facts)", 0.7, 0.7, 0.7, false)
        else
            for _, fact in ipairs(overlay.facts) do
                local r, g, b = fact.superseded and 0.55 or 0.45,
                    fact.superseded and 0.55 or 0.9, fact.superseded and 0.55 or 1
                local state = fact.superseded and "SUPERSEDED" or "ACTIVE"
                shadowText(UIFont.Small, x, y, fact.kind .. "  " .. fact.source
                    .. "  [" .. fact.confidence .. "]  " .. state, r, g, b, false)
                y = y + 17
                local text = fact.text:gsub("[\r\n]", " ")
                if #text > 88 then text = text:sub(1, 85) .. "..." end
                shadowText(UIFont.Small, x + 14, y, text, r, g, b, false)
                y = y + 17
            end
        end
    elseif #overlay.events == 0 then
        shadowText(UIFont.Small, x, y, "(no pending events)", 0.7, 0.7, 0.7, false)
    else
        for _, event in ipairs(overlay.events) do
            local r, g, b = 1, 0.85, 0.3
            if event.importance >= 75 then r, g, b = 1, 0.3, 0.25 end
            if event.narrated > 0 then r, g, b = 0.55, 0.75, 0.65 end
            local at = event.stamp ~= "" and (event.stamp .. "  ") or ""
            local state = event.narrated > 0 and ("PAGE " .. event.narrated) or "PENDING"
            shadowText(UIFont.Small, x, y,
                at .. event.kind .. "  [" .. event.importance .. "]  " .. state,
                r, g, b, false)
            y = y + 17
            local summary = event.summary:gsub("[\r\n]", " ")
            if #summary > 88 then summary = summary:sub(1, 85) .. "..." end
            shadowText(UIFont.Small, x + 14, y, summary, r * 0.9, g * 0.9, b * 0.9, false)
            y = y + 17
        end
    end
    y = y + 4
    shadowText(UIFont.Small, x, y, "F5: inbox/history/facts/threads/evidence  |  F8: close",
        0.65, 0.75, 0.8, false)
end

local function toggleOverlay()
    overlay.visible = not overlay.visible
    overlay.nextRefresh = 0
    refreshOverlay()
    notify("PZStory Testing Mode: " .. (overlay.visible and "ON" or "OFF"), true)
end

local function switchInbox()
    if not overlay.visible then overlay.visible = true end
    overlay.mode = overlay.mode == "pending" and "recent"
        or (overlay.mode == "recent" and "facts"
        or (overlay.mode == "facts" and "threads"
        or (overlay.mode == "threads" and "continuity" or "pending")))
    overlay.nextRefresh = 0
    refreshOverlay()
    notify("PZStory Inbox: " .. overlay.mode:upper(), true)
end

-- ------------------------------------------------------- guided Test Lab

-- The lab is deliberately available only in the development probe and only
-- while the game itself is running with -debug. It never calls the provider,
-- never edits campaign memory directly, and judges the real event journal
-- produced by the normal Lua/Java integration.
local lab = {
    visible = false,
    running = false,
    suite = 1,
    step = 0,
    title = "READY",
    instruction = "F3: run quick checks  |  F4: close",
    results = {},
    baseline = {},
    deadline = 0,
}

local suites = { "QUICK CHECKS", "AUTOMATED SCENARIOS",
    "EVENT WALKTHROUGH", "CONTINUITY WALKTHROUGH" }

local function debugEnabled()
    local enabled = false
    pcall(function() enabled = isDebugEnabled() == true end)
    return enabled
end

local function labResult(name, pass, detail)
    table.insert(lab.results, {
        name = name,
        pass = pass == true,
        detail = tostring(detail or ""),
    })
    if #lab.results > 12 then table.remove(lab.results, 1) end
    print("[PZStoryTestLab] " .. (pass and "PASS" or "FAIL") .. " | "
        .. name .. (detail and (" | " .. tostring(detail)) or ""))
end

local function journalEvents()
    local root = decodeDiagnostic("eventJournal")
    if root == nil or type(root.events) ~= "table" then return {} end
    return root.events
end

local function eventCount(kind)
    local count = 0
    for _, event in ipairs(journalEvents()) do
        if type(event) == "table" and tostring(event.type or "") == kind then
            count = count + 1
        end
    end
    return count
end

local function rememberCounts()
    lab.baseline = {}
    local kinds = { "door_opened", "door_closed", "vehicle_entered", "vehicle_exited",
        "kill", "item_acquired", "craft_completed", "repair_completed",
        "sleep_started", "sleep_ended" }
    for _, kind in ipairs(kinds) do lab.baseline[kind] = eventCount(kind) end
end

local function sawAfter(kind)
    return eventCount(kind) > (lab.baseline[kind] or 0)
end

local function prepareWeaponFixture()
    local pl = getPlayer()
    if pl == nil then return false, "no player" end
    local weapon
    local ok, err = pcall(function()
        weapon = pl:getInventory():AddItem("Base.Axe")
        if weapon ~= nil then
            pl:setPrimaryHandItem(weapon)
            if weapon:isTwoHandWeapon() then pl:setSecondaryHandItem(weapon) end
        end
        -- This is the same Build 42 primitive used by the installed horde
        -- debugger. Place two ordinary zombies close enough for a controlled
        -- combat test, but never kill them or alter the player's kill count.
        addZombiesInOutfit(math.floor(pl:getX()) + 3, math.floor(pl:getY()),
            math.floor(pl:getZ()), 2, nil, 50, false, false, false, false,
            false, false, 1.0, false, 0, false)
    end)
    if not ok then return false, err end
    return weapon ~= nil, weapon and "axe equipped; two zombies spawned" or "axe unavailable"
end

local function runQuickChecks()
    lab.results = {}
    local methods = { "version", "apiVersion", "eventJournal", "worldMemory",
        "factMemory", "threadMemory", "continuityMemory", "providerPreview",
        "testLabScenario" }
    labResult("game debug mode", debugEnabled(), debugEnabled() and "enabled" or "launch with -debug")
    labResult("Java bridge", type(PZStory) == "table", type(PZStory))
    if type(PZStory) ~= "table" then return end
    for _, method in ipairs(methods) do
        labResult("bridge: " .. method, type(PZStory[method]) == "function")
    end
    for _, method in ipairs({ "eventJournal", "worldMemory", "factMemory",
            "threadMemory", "continuityMemory" }) do
        labResult(method .. " JSON", decodeDiagnostic(method) ~= nil)
    end
    local preview = ""
    local ok = pcall(function() preview = tostring(PZStory.providerPreview() or "") end)
    local pl = getPlayer()
    local coordinateLeak = false
    if ok and pl ~= nil then
        local x, y = tostring(math.floor(pl:getX())), tostring(math.floor(pl:getY()))
        coordinateLeak = preview:find('"x"%s*:%s*' .. x) ~= nil
            or preview:find('"y"%s*:%s*' .. y) ~= nil
    end
    labResult("provider preview", ok and preview ~= "", #preview .. " chars")
    labResult("exact coordinates withheld", ok and not coordinateLeak)
    lab.title = "QUICK CHECKS COMPLETE"
    lab.instruction = "Review PASS/FAIL below. F3 repeats; F4 closes."
end

local function runSyntheticScenario(scenario)
    lab.results = {}
    if type(PZStory) ~= "table" or type(PZStory.testLabScenario) ~= "function" then
        labResult("automated scenario bridge", false, "restart after installing the new JAR")
        lab.title = "AUTOMATED SCENARIOS UNAVAILABLE"
        return
    end
    local decoded
    local ok, err = pcall(function()
        decoded = PZStoryJSONDecode(PZStory.testLabScenario(scenario))
    end)
    if not ok or type(decoded) ~= "table" or type(decoded.checks) ~= "table" then
        labResult("automated scenario response", false, err or "invalid JSON")
        lab.title = "AUTOMATED SCENARIOS FAILED"
        return
    end
    for _, check in ipairs(decoded.checks) do
        if type(check) == "table" then
            labResult(tostring(check.name or "scenario check"), check.pass == true,
                tostring(check.detail or ""))
        end
    end
    labResult("save unchanged", decoded.saveChanged == false, "in-memory fixture")
    lab.title = "AUTOMATED " .. tostring(scenario):upper() .. " COMPLETE"
    lab.instruction = tostring(decoded.passed or 0) .. "/"
        .. tostring(decoded.total or 0) .. " scenario checks passed; no campaign data changed."
end

local function beginWalkthrough(continuity)
    lab.results = {}
    lab.running = true
    lab.step = continuity and 10 or 1
    rememberCounts()
    lab.deadline = getTimestampMs() + 120000
    if continuity then
        local ok, detail = prepareWeaponFixture()
        labResult("prepare weapon fixture", ok, detail)
        lab.title = "CONTINUITY 1/2"
        lab.instruction = "Kill both spawned zombies with the equipped axe."
    else
        lab.title = "EVENTS 1/3"
        lab.instruction = "Open a nearby ordinary door. The lab will detect it."
    end
end

local function checkWalkthrough()
    if not lab.running then return end
    if getTimestampMs() > lab.deadline then
        labResult("step timed out", false, lab.instruction)
        lab.running = false
        lab.title = "WALKTHROUGH PAUSED"
        lab.instruction = "F3 restarts the suite."
        return
    end
    if lab.step == 1 and sawAfter("door_opened") then
        labResult("door opened", true)
        lab.step = 2
        lab.title = "EVENTS 2/3"
        lab.instruction = "Close the same door."
    elseif lab.step == 2 and sawAfter("door_closed") then
        labResult("door closed", true)
        lab.step = 3
        lab.title = "EVENTS 3/3"
        lab.instruction = "Enter a vehicle, then exit it."
    elseif lab.step == 3 and sawAfter("vehicle_entered") and sawAfter("vehicle_exited") then
        labResult("vehicle entered", true)
        labResult("vehicle exited", true)
        lab.running = false
        lab.title = "EVENT WALKTHROUGH COMPLETE"
        lab.instruction = "All real gameplay hooks passed."
    elseif lab.step == 10 and eventCount("kill") >= (lab.baseline.kill or 0) + 2 then
        labResult("two real kills detected", true)
        lab.step = 11
        lab.title = "CONTINUITY 2/2"
        lab.instruction = "Waiting for same-weapon familiarity evidence..."
    elseif lab.step == 11 then
        local root = decodeDiagnostic("continuityMemory")
        local memory = root and root.continuityMemory
        local found = false
        if memory and type(memory.entries) == "table" then
            for _, entry in ipairs(memory.entries) do
                if type(entry) == "table" and entry.kind == "weapon"
                        and (tonumber(entry.occurrences) or 0) >= 2 then
                    found = true
                end
            end
        end
        if found then
            labResult("weapon familiarity", true, "two observations promoted")
            lab.running = false
            lab.title = "CONTINUITY COMPLETE"
            lab.instruction = "The exact weapon was remembered correctly."
        end
    end
end

local function runLabSuite()
    if not lab.visible then lab.visible = true end
    if not debugEnabled() then
        lab.results = {}
        labResult("game debug mode", false, "restart Project Zomboid with -debug")
        lab.title = "DEBUG MODE REQUIRED"
        lab.instruction = "The Test Lab will not modify a normal game."
        return
    end
    if lab.suite == 1 then runQuickChecks()
    elseif lab.suite == 2 then runSyntheticScenario("all")
    elseif lab.suite == 3 then beginWalkthrough(false)
    else beginWalkthrough(true) end
end

local function toggleLab()
    if not debugEnabled() then
        lab.visible = true
        lab.results = {}
        labResult("game debug mode", false, "restart Project Zomboid with -debug")
        lab.title = "DEBUG MODE REQUIRED"
        lab.instruction = "F4 closes. No save data was changed."
        return
    end
    if not lab.visible then
        lab.visible = true
        lab.title = suites[lab.suite]
        lab.instruction = "F3: run  |  F4: next suite/close after third"
    elseif not lab.running then
        if lab.suite == #suites then
            lab.visible = false
            lab.suite = 1
            return
        end
        lab.suite = lab.suite + 1
        lab.title = suites[lab.suite]
        lab.instruction = "F3: run this suite  |  F4: next suite"
        lab.results = {}
    else
        lab.running = false
        lab.visible = false
    end
end

local labPanel = nil

local function labButtonClick(_, button)
    local action = button and button.internal or ""
    if action == "quick" then runQuickChecks()
    elseif action == "events" then beginWalkthrough(false)
    elseif action == "continuity-real" then beginWalkthrough(true)
    elseif action == "close" then
        lab.running = false
        lab.visible = false
    else runSyntheticScenario(action) end
end

local function ensureLabPanel()
    if labPanel ~= nil then return end
    local pl = getPlayer()
    if pl == nil then return end
    local left = getPlayerScreenLeft(pl:getPlayerNum()) + 18
    local top = getPlayerScreenTop(pl:getPlayerNum()) + 210
    labPanel = ISPanel:new(left, top, 760, 104)
    labPanel:initialise()
    labPanel.backgroundColor = { r = 0.03, g = 0.04, b = 0.05, a = 0.82 }
    labPanel.borderColor = { r = 0.35, g = 0.9, b = 0.55, a = 0.9 }
    labPanel:addToUIManager()
    local specs = {
        { "QUICK", "quick" }, { "ALL AUTO", "all" }, { "PLACE", "place" },
        { "DOORS", "door" }, { "VEHICLE", "vehicle" }, { "NOISE", "noise" },
        { "KILL", "kill" }, { "TIME", "time" }, { "MEMORY", "continuity" },
        { "REAL EVENTS", "events" }, { "REAL KILLS", "continuity-real" },
        { "CLOSE", "close" },
    }
    for i, spec in ipairs(specs) do
        local col = (i - 1) % 6
        local row = math.floor((i - 1) / 6)
        local button = ISButton:new(8 + col * 125, 8 + row * 44, 117, 34,
            spec[1], nil, labButtonClick)
        button.internal = spec[2]
        button:initialise()
        labPanel:addChild(button)
    end
end

local function drawLab()
    ensureLabPanel()
    if labPanel ~= nil then labPanel:setVisible(lab.visible and debugEnabled()) end
    if not lab.visible then return end
    local pl = getPlayer()
    if pl == nil then return end
    local x = getPlayerScreenLeft(pl:getPlayerNum()) + 24
    local y = getPlayerScreenTop(pl:getPlayerNum()) + 330
    shadowText(UIFont.Medium, x, y, "PZSTORY TEST LAB — " .. lab.title,
        0.55, 1, 0.75, false)
    y = y + 23
    shadowText(UIFont.Small, x, y, lab.instruction, 0.9, 0.9, 0.9, false)
    y = y + 20
    for _, result in ipairs(lab.results) do
        local label = (result.pass and "PASS  " or "FAIL  ") .. result.name
        if result.detail ~= "" then label = label .. " — " .. result.detail end
        if #label > 100 then label = label:sub(1, 97) .. "..." end
        shadowText(UIFont.Small, x, y, label,
            result.pass and 0.45 or 1, result.pass and 1 or 0.35,
            result.pass and 0.55 or 0.3, false)
        y = y + 17
    end
end

-- Named console entry points make the lab usable by unattended UI harnesses
-- whose synthetic function keys are ignored by Project Zomboid's low-level
-- input polling. They remain debug-probe functions and retain debugEnabled()
-- gating before any fixture can be prepared.
PZStoryTestLabToggle = toggleLab
PZStoryTestLabRun = runLabSuite
PZStoryTestLabScenario = runSyntheticScenario

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
    -- A debug launch is itself an explicit request to inspect the build. Run
    -- the read-only suite automatically so headless/unattended harnesses do
    -- not depend on Project Zomboid accepting synthetic keyboard input.
    if debugEnabled() then
        lab.visible = true
        runQuickChecks()
    end
end)

Events.OnKeyPressed.Add(function(key)
    -- Existing keysB42.ini files do not gain a persisted value merely because
    -- a mod registers a new binding. Honour an assigned value, but keep the
    -- documented physical defaults usable on upgraded profiles.
    local labKey = getCore():getKey(LAB_BIND)
    local labRunKey = getCore():getKey(LAB_RUN_BIND)
    if key == Keyboard.KEY_F4 or (labKey ~= nil and labKey > 0 and key == labKey) then
        toggleLab()
    end
    if key == Keyboard.KEY_F3 or (labRunKey ~= nil and labRunKey > 0 and key == labRunKey) then
        runLabSuite()
    end
    if key == getCore():getKey(OVERLAY_BIND) then toggleOverlay() end
    if key == getCore():getKey(INBOX_BIND) then switchInbox() end
    if key == getCore():getKey(SNAPSHOT_BIND) then takeSnapshot("keypress") end
    if key == getCore():getKey(TEST_BIND) then runSelfTest() end
    if key == getCore():getKey(EVENTS_BIND) then
        dumpLocal("eventJournal", "PZStoryEvents", "event journal")
    end
    if key == getCore():getKey(MEMORY_BIND) then
        dumpLocal("worldMemory", "PZStoryWorldMemory", "world memory")
    end
end)

Events.OnTick.Add(onTick)
Events.OnTick.Add(checkWalkthrough)
Events.OnTickEvenPaused.Add(refreshOverlay)
Events.OnPostUIDraw.Add(drawOverlay)
Events.OnPostUIDraw.Add(drawLab)
