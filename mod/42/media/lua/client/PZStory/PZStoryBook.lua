--[[
  PZStory - the book.

  A portrait handheld by Premium Technologies, the same firm that makes the
  radios, generators and televisions found around Knox County. Cool slate
  casing sampled from their CRT set; recessed reflective LCD; hardware buttons.

  Drawn entirely with primitives - no textures to ship, nothing to keep in sync
  with an art file, and it scales cleanly to any monitor.

  Three engine facts this depends on, all verified in the game's own code:
    * setGameSpeed(0) plus getGameTime():setMultiplier() is how PZ's own
      SpeedControlsHandler pauses, so the pause holds.
    * OnTickEvenPaused exists. OnTick does NOT fire at speed zero, and the book
      pauses the game the moment it opens.
    * UIFont.CodeSmall/Code/CodeMedium/CodeLarge are four sizes of the same
      monospace face, so zoom keeps an exact character grid instead of
      degrading to proportional text.
]]

-- Load the Build 42 timed-action classes before installing completion hooks.
-- All paths below are shipped by the game; explicit loading avoids silently
-- losing coverage when another UI module has not happened to require them.
require "TimedActions/ISCraftAction"
require "TimedActions/ISRepairClothing"
require "TimedActions/ISEatFoodAction"
require "TimedActions/ISDrinkFromBottle"
require "TimedActions/ISDrinkFluidAction"
require "TimedActions/ISOpenCloseDoor"
require "Vehicles/TimedActions/ISRepairEngine"
require "Vehicles/TimedActions/ISRepairLightbar"
require "Farming/TimedActions/ISSeedActionNew"
require "Farming/TimedActions/ISWaterPlantAction"
require "Camping/TimedActions/ISLightFromPetrol"
require "Camping/TimedActions/ISLightFromLiterature"
require "Camping/TimedActions/ISLightFromKindle"

PZStoryBook = ISPanel:derive("PZStoryBook")

-- ------------------------------------------------------------------ palette
-- Premium Technologies house colours, sampled from their widescreen CRT:
-- cool slate grey, 215-225 degrees hue, 8-12% saturation.
local C = {
    rim      = {0.635, 0.651, 0.682},  -- #A2A6AE top-facing plane, light catch
    front    = {0.459, 0.478, 0.514},  -- #757A83 front body and outer bezel
    side     = {0.361, 0.380, 0.420},  -- #5C616B mid-shadow side housing
    glass    = {0.227, 0.235, 0.259},  -- #3A3C42 unpowered display face
    recess   = {0.204, 0.216, 0.243},  -- #34373E undercuts and bevels
    outline  = {0.106, 0.114, 0.129},  -- #1B1D21 inner borders, contour pixels

    -- The screen when it is on. Kept pale so text stays black-on-light: this
    -- is a reflective LCD, not a CRT, and legibility was the whole reason for
    -- choosing this device over handwriting.
    lcd      = {0.706, 0.733, 0.757},
    lcdEdge  = {0.522, 0.553, 0.580},
    ink      = {0.106, 0.114, 0.129},
    inkDim   = {0.322, 0.345, 0.373},
    led      = {0.78, 0.27, 0.18},

    -- The power button. A lit green lens in the top-left corner of the case,
    -- the way every piece of nineties consumer electronics wore it. Kept cool
    -- and slightly desaturated so it belongs on the slate rather than glowing
    -- like a modern indicator.
    power     = {0.298, 0.706, 0.396},
    powerLit  = {0.478, 0.855, 0.549},
    powerDark = {0.129, 0.318, 0.176},
}

-- Zoom steps. Each is a real monospace font, so the grid stays exact.
local ZOOM = {
    { font = UIFont.CodeSmall,  label = "S" },
    { font = UIFont.Code,       label = "M" },
    { font = UIFont.CodeMedium, label = "L" },
    { font = UIFont.CodeLarge,  label = "XL" },
}

-- Nameplate. Period-correct arrangement: the product name sits top-left and
-- the maker top-right. Lowercase was the modern look for a product name in
-- the early nineties - the manufacturer keeps its normal wordmark casing.
local MODEL = "pilot 3000"
local BRAND = "Premium Tech."

-- The Java half this file expects. Lua reloads when a save loads; the JAR only
-- reloads when Project Zomboid itself restarts. During development the two
-- WILL get out of step, and an unguarded call then throws on every tick and
-- buries the log. Check once, say so plainly, and refuse to go on.
-- The BRIDGE API version this file needs, compared for EXACT equality.
--
-- Not the release version. The two were the same string until 1.24.0, which
-- had two bad consequences. Every cosmetic release forced a firmware mismatch
-- and a full restart of Project Zomboid, and the comparison was a PREFIX
-- match: a JAR reporting "1.23.10" satisfied a file requiring "1.23.1", so an
-- incompatible pairing could load and then fail one call at a time.
--
-- Bump this only when the Java surface this file calls actually changes, and
-- bump Version.API in Java to match. build.sh refuses to build if they differ.
local NEEDS_API = "8"

-- The three note types, and what each one DOES. The lifetime is the point of
-- asking the player to choose, so the device says it out loud rather than
-- leaving them to guess.
-- Written in the player's own voice. He is making a note to himself in his own
-- device, not filing a request with something that lives inside it.
local NOTE_TYPES = {
    -- `answer` decides whether writing this kind of note should immediately
    -- produce a page about it. An observation and a decision are events and
    -- deserve an answer; a standing preference is a dial, and turning a dial
    -- should not cost a chapter.
    { id = "observation", label = "NOTICED", answer = true,
      blurb = "something I noticed. worth remembering." },
    { id = "direction",   label = "NEXT", answer = true,
      blurb = "what I mean to do next. just this once." },
    { id = "standing",    label = "ALWAYS", answer = false,
      blurb = "how I want things to go, until I say otherwise." },
}

--- Returns the named PZStory function, or nil if this JAR is too old.
local function api(name)
    if PZStory == nil then return nil end
    local fn = PZStory[name]
    if type(fn) == "function" then return fn end
    return nil
end

local function javaVersion()
    local v
    if not pcall(function() v = PZStory.version() end) then return "?" end
    return v or "?"
end

--- The bridge API version the loaded JAR reports, or nil if it is too old to
--- have apiVersion() at all (anything before release 1.24.0).
local function javaApi()
    if PZStory == nil or type(PZStory.apiVersion) ~= "function" then return nil end
    local v
    if not pcall(function() v = PZStory.apiVersion() end) then return nil end
    return v
end
local COLS  = 46      -- prose columns, constant at every zoom step
local ROWS  = 26

-- ------------------------------------------------------------------ helpers

--- True average character advance for a monospace font.
---
--- Measuring a single glyph underestimates the advance, and the error is
--- multiplied by the column count - invisible at CodeSmall, catastrophic at
--- CodeLarge, which is exactly how the overflow presented. Measuring a long
--- run and dividing includes inter-character spacing and is exact.
local function advance(font)
    local n = 40
    local w = getTextManager():MeasureStringX(font, string.rep("M", n))
    if w == nil or w <= 0 then return 8 end
    return w / n
end

--- The Code font has no glyph for typographic punctuation, so an em dash
--- renders as "?" - visible in the first real page as "dragged at him ?".
--- Models reach for these constantly, so fold them to ASCII rather than
--- trying to forbid them in the prompt.
local function asciify(s)
    if s == nil then return "" end
    s = s:gsub("\226\128\148", " - ")    -- em dash
    s = s:gsub("\226\128\147", "-")      -- en dash
    s = s:gsub("\226\128\152", "'")      -- left single quote
    s = s:gsub("\226\128\153", "'")      -- right single quote / apostrophe
    s = s:gsub("\226\128\156", '"')      -- left double quote
    s = s:gsub("\226\128\157", '"')      -- right double quote
    s = s:gsub("\226\128\166", "...")    -- ellipsis
    s = s:gsub("\194\160", " ")          -- non-breaking space
    return s
end

-- -------------------------------------------------------------------- JSON
-- Java returns JSON because Kahlua does not preserve ordinary Java collection
-- types reliably across versions. JSON is a grammar, not a regular language:
-- braces and escaped quotes inside player/model text made the former gmatch()
-- extraction split valid records. Keep one strict decoder at the boundary.
local JSON_NULL = {}

local function jsonUtf8(cp)
    if cp <= 0x7F then return string.char(cp) end
    if cp <= 0x7FF then
        return string.char(0xC0 + math.floor(cp / 0x40), 0x80 + (cp % 0x40))
    end
    if cp <= 0xFFFF then
        return string.char(0xE0 + math.floor(cp / 0x1000),
            0x80 + (math.floor(cp / 0x40) % 0x40), 0x80 + (cp % 0x40))
    end
    return string.char(0xF0 + math.floor(cp / 0x40000),
        0x80 + (math.floor(cp / 0x1000) % 0x40),
        0x80 + (math.floor(cp / 0x40) % 0x40), 0x80 + (cp % 0x40))
end

local function jsonDecode(text)
    if type(text) ~= "string" then error("JSON input is not a string") end
    if #text > 2 * 1024 * 1024 then error("JSON input is too large") end
    local at, depth = 1, 0

    local function fail(message)
        error(message .. " at byte " .. at)
    end
    local function ws()
        while true do
            local c = text:sub(at, at)
            if c == " " or c == "\t" or c == "\r" or c == "\n" then at = at + 1
            else return end
        end
    end
    local function hex4()
        local h = text:sub(at, at + 3)
        if #h ~= 4 or not h:match("^[0-9A-Fa-f]+$") then fail("bad unicode escape") end
        at = at + 4
        return tonumber(h, 16)
    end
    local function str()
        if text:sub(at, at) ~= '"' then fail("expected string") end
        at = at + 1
        local out = {}
        while at <= #text do
            local c = text:sub(at, at)
            at = at + 1
            if c == '"' then return table.concat(out) end
            if c == "\\" then
                local e = text:sub(at, at)
                at = at + 1
                local simple = { ['"']='"', ['\\']='\\', ['/']='/',
                    b='\b', f='\f', n='\n', r='\r', t='\t' }
                if simple[e] ~= nil then
                    table.insert(out, simple[e])
                elseif e == "u" then
                    local cp = hex4()
                    if cp >= 0xD800 and cp <= 0xDBFF then
                        if text:sub(at, at + 1) ~= "\\u" then fail("unpaired high surrogate") end
                        at = at + 2
                        local low = hex4()
                        if low < 0xDC00 or low > 0xDFFF then fail("bad low surrogate") end
                        cp = 0x10000 + (cp - 0xD800) * 0x400 + (low - 0xDC00)
                    elseif cp >= 0xDC00 and cp <= 0xDFFF then
                        fail("unpaired low surrogate")
                    end
                    table.insert(out, jsonUtf8(cp))
                else
                    fail("bad string escape")
                end
            else
                if c:byte() < 0x20 then fail("control character in string") end
                table.insert(out, c)
            end
        end
        fail("unterminated string")
    end

    local value
    local function numberValue()
        local start = at
        if text:sub(at, at) == "-" then at = at + 1 end
        local first = text:sub(at, at)
        if first == "0" then
            at = at + 1
        elseif first:match("[1-9]") then
            repeat at = at + 1 until not text:sub(at, at):match("%d")
        else
            fail("bad number")
        end
        if text:sub(at, at) == "." then
            at = at + 1
            if not text:sub(at, at):match("%d") then fail("bad fraction") end
            repeat at = at + 1 until not text:sub(at, at):match("%d")
        end
        local e = text:sub(at, at)
        if e == "e" or e == "E" then
            at = at + 1
            local sign = text:sub(at, at)
            if sign == "+" or sign == "-" then at = at + 1 end
            if not text:sub(at, at):match("%d") then fail("bad exponent") end
            repeat at = at + 1 until not text:sub(at, at):match("%d")
        end
        local n = tonumber(text:sub(start, at - 1))
        if n == nil then fail("bad number") end
        return n
    end

    value = function()
        ws()
        local c = text:sub(at, at)
        if c == '"' then return str() end
        if c == "-" or c:match("%d") then return numberValue() end
        if text:sub(at, at + 3) == "true" then at = at + 4; return true end
        if text:sub(at, at + 4) == "false" then at = at + 5; return false end
        if text:sub(at, at + 3) == "null" then at = at + 4; return JSON_NULL end
        if c ~= "{" and c ~= "[" then fail("expected value") end

        depth = depth + 1
        if depth > 100 then fail("JSON nested too deeply") end
        local result = {}
        at = at + 1
        ws()
        local close = c == "{" and "}" or "]"
        if text:sub(at, at) == close then at = at + 1; depth = depth - 1; return result end
        local index = 1
        while true do
            if c == "{" then
                ws()
                local key = str()
                ws()
                if text:sub(at, at) ~= ":" then fail("expected colon") end
                at = at + 1
                result[key] = value()
            else
                result[index] = value()
                index = index + 1
            end
            ws()
            local sep = text:sub(at, at)
            if sep == close then at = at + 1; depth = depth - 1; return result end
            if sep ~= "," then fail("expected comma or closing bracket") end
            at = at + 1
        end
    end

    local result = value()
    ws()
    if at <= #text then fail("trailing JSON data") end
    return result
end

local function decodeObject(raw)
    local ok, result = pcall(jsonDecode, raw)
    if ok and type(result) == "table" then return result end
    return nil
end

-- The optional development probe is a separate Lua file. Publish the decoder
-- so it does not reintroduce regex extraction for streamed JSON.
PZStoryJSONDecode = jsonDecode

local function rgb(self, x, y, w, h, c, a)
    self:drawRect(x, y, w, h, a or 1.0, c[1], c[2], c[3])
end

local function border(self, x, y, w, h, c, a)
    self:drawRectBorder(x, y, w, h, a or 1.0, c[1], c[2], c[3])
end

--- A rounded rectangle, built from a chamfered stack of 1px rows. The engine
--- has no rounded primitive, and at these sizes a 2-3px chamfer reads as a
--- radius - which is what injection-moulded 1990s keys actually look like.
local function roundRect(self, x, y, w, h, r, c, a)
    a = a or 1.0
    r = math.max(0, math.min(r, math.floor(math.min(w, h) / 2)))
    for i = 0, r - 1 do
        local ins = r - i
        self:drawRect(x + ins, y + i,         w - ins * 2, 1, a, c[1], c[2], c[3])
        self:drawRect(x + ins, y + h - 1 - i, w - ins * 2, 1, a, c[1], c[2], c[3])
    end
    self:drawRect(x, y + r, w, h - r * 2, a, c[1], c[2], c[3])
end

--- A real filled circle, scanline by scanline.
---
--- roundRect() chamfers by one pixel per row, so asking it for a radius of
--- half the width does not give a disc - it gives a diamond, which is exactly
--- what the power button came out as. A lens has to be round.
local function disc(self, cx, cy, rad, c, a)
    a = a or 1.0
    for dy = -rad, rad do
        local dx = math.floor(math.sqrt(math.max(0, rad * rad - dy * dy)) + 0.5)
        if dx > 0 then
            self:drawRect(cx - dx, cy + dy, dx * 2, 1, a, c[1], c[2], c[3])
        end
    end
end

--- Text cut into the plastic.
---
--- Light comes from above, so an engraved groove is dark along its top wall
--- and catches a highlight on the lower one. Drawing the highlight first,
--- offset down-right, and the dark cut over it at full position gives that
--- read. No solid ink colour is used at all - the case shows through, which
--- is what separates "moulded in" from "printed on".
local function engrave(self, text, x, y, font, a)
    a = a or 1.0
    self:drawText(text, x + 1, y + 1, C.rim[1], C.rim[2], C.rim[3], a * 0.55, font)
    self:drawText(text, x,     y,     C.recess[1], C.recess[2], C.recess[3], a, font)
end

--- Wraps to a column count. Monospace makes this exact rather than
--- approximate, which is half the reason this look earns its keep.
local function wrap(text, cols)
    local out = {}
    local first = true
    for _, paragraph in ipairs(luautils.split(asciify(text), "\n")) do
        if paragraph == "" then
            table.insert(out, "")
        else
            -- A blank line between paragraphs. On a dense monospace grid the
            -- prose reads as one slab without it.
            if not first then table.insert(out, "") end
            first = false
            local line = ""
            for word in paragraph:gmatch("%S+") do
                if line == "" then line = word
                elseif #line + 1 + #word <= cols then line = line .. " " .. word
                else table.insert(out, line); line = word end
                -- A word longer than the column count must still break, or it
                -- silently runs off the screen edge.
                while #line > cols do
                    table.insert(out, line:sub(1, cols))
                    line = line:sub(cols + 1)
                end
            end
            if line ~= "" then table.insert(out, line) end
        end
    end
    return out
end

-- ------------------------------------------------------------------- window

function PZStoryBook:new()
    local o = ISPanel:new(0, 0, 100, 100)
    setmetatable(o, self)
    self.__index = self
    o.backgroundColor = {r = 0, g = 0, b = 0, a = 0}
    o.borderColor     = {r = 0, g = 0, b = 0, a = 0}
    o.moveWithMouse   = true

    o.pageTitle  = ""
    o.pageText   = ""
    o.statusLine = "ready"
    o.scroll     = 0
    o.maxScroll  = 0
    o.streaming  = false
    o.chunks     = 0
    o.prevSpeed  = nil
    o.timepiece  = "none"
    -- The single source of truth for "is the device on". See toggle().
    o.isOpen     = false
    o.mode       = "page"           -- "page" | "note"
    o.noteType   = 1
    o.standing   = {}
    -- A remembered zoom beats a guess from the screen height.
    local saved = 0
    local gz = api("getZoom")
    if gz then pcall(function() saved = gz() end) end
    o.zoom       = (saved and saved >= 1 and saved <= #ZOOM) and saved or o:defaultZoom()
    o:applyZoom(true)
    return o
end

--- The LCD rectangle. Needed by both the renderer and the text-entry layout,
--- so it lives in one place rather than being recomputed slightly differently.
function PZStoryBook:screenRect()
    -- Defensive defaults: if a metric is ever missing, return a usable
    -- rectangle rather than throwing once per frame for the rest of the
    -- session. A wrong layout is debuggable; a wall of errors is not.
    local pad   = self.pad   or 12
    local nameH = self.nameH or 0
    local skirt = self.skirt or 26
    local hwH   = self.hwH   or 28
    local w, h  = self:getWidth(), self:getHeight()
    local top   = pad + nameH
    return pad, top, w - pad * 2, h - top - pad - skirt - hwH
end

--- The soft keys live ON the glass, not on the case.
---
--- A 1993 handheld had a few fixed hardware controls and everything else drawn
--- on screen for the stylus. A physical key that relabels itself is the one
--- detail that breaks the object, so the case now carries only what genuinely
--- never changes: the power light and the display rocker.
function PZStoryBook:softKeys(labels)
    local sx, sy, sw, sh = self:screenRect()
    local x = sx + self.inset
    local innerW = sw - self.inset * 2
    local y = sy + sh - self.inset - self.lineH - 4 - self.btnH
    local n, gap = #labels, math.max(3, math.floor(self.charW / 2))
    local bw = math.floor((innerW - gap * (n - 1)) / n)
    local out = {}
    for i = 1, n do
        out[i] = { x = x + (i - 1) * (bw + gap), y = y, w = bw, h = self.btnH, id = i }
    end
    return out, y
end

function PZStoryBook:createChildren()
    if ISPanel.createChildren then ISPanel.createChildren(self) end
    -- One multi-line box, reused for every note. Created once so focus and
    -- caret behaviour stay consistent.
    self.entry = ISTextEntryBox:new("", 0, 0, 10, 10)
    self.entry:initialise()
    self.entry:instantiate()
    self.entry:setMultipleLine(true)
    self.entry:setMaxLines(5)
    self.entry:setMaxTextLength(480)
    self.entry:setPlaceholderText("write what you are thinking")
    self.entry:setVisible(false)
    -- Dressed as part of the display rather than as a game dialog. The default
    -- dark slab sat on the LCD looking like a hole punched in the glass.
    self.entry.backgroundColor = { r = C.lcd[1] * 0.93, g = C.lcd[2] * 0.93,
                                   b = C.lcd[3] * 0.93, a = 1 }
    self.entry.borderColor     = { r = C.lcdEdge[1], g = C.lcdEdge[2],
                                   b = C.lcdEdge[3], a = 1 }
    self:addChild(self.entry)
    self:layoutEntry()
end

function PZStoryBook:layoutEntry()
    if self.entry == nil then return end
    local sx, sy, sw, sh = self:screenRect()
    local x = sx + self.inset
    local w = sw - self.inset * 2
    local y = sy + self.inset + self.lineH * 3
    self.entry:setX(x)
    self.entry:setY(y)
    self.entry:setWidth(w)
    self.entry:setHeight(self.lineH * 5 + 8)
    self.entry:setFont(self.font)
end

--- Recomputes metrics and the case size for the current zoom step.
function PZStoryBook:applyZoom(firstTime)
    -- Keep the device centred on its own middle, so a zoom step grows the case
    -- outwards in every direction instead of anchoring the top-left corner and
    -- walking the whole thing across the screen.
    --
    -- getX()/getWidth() go through the Java object, which does not exist until
    -- the panel is instantiated. Calling them from new() throws before any
    -- metric is assigned, and every later screenRect() then fails on nil - so
    -- only ask for the old centre when there is one.
    local oldCX, oldCY
    if not firstTime then
        oldCX = self:getX() + self:getWidth() / 2
        oldCY = self:getY() + self:getHeight() / 2
    end

    self.font  = ZOOM[self.zoom].font
    self.charW = advance(self.font)
    self.lineH = getTextManager():getFontHeight(self.font) + 2

    -- The case is sized FROM the type: always COLS columns by ROWS rows,
    -- whatever the font. Zoom makes the text bigger rather than fitting more
    -- of it in, and the screen well is measured from a real string so the
    -- prose cannot outgrow it.
    self.pad   = math.max(12, math.floor(self.charW * 1.6))
    self.inset = math.max(8, math.floor(self.charW))
    self.btnH  = self.lineH + 8           -- soft-key height, ON the glass
    -- The power lens is the tallest thing in the nameplate strip, so the strip
    -- is sized from it rather than from the type.
    self.powerD = self.lineH + 4
    self.nameH = math.max(self.lineH + 10, self.powerD + 8)
    self.skirt = self.lineH + 10          -- rocker and power light
    self.hwH   = self.lineH + 14          -- the fixed hardware key row

    local textW = getTextManager():MeasureStringX(self.font, string.rep("M", COLS))
    local w = textW + self.inset * 2 + self.pad * 2
    local h = ROWS * self.lineH + self.inset * 2 + self.pad * 2
            + self.nameH + self.skirt + self.hwH

    local sw, sh = getCore():getScreenWidth(), getCore():getScreenHeight()
    -- Never let a zoom step push the device off a smaller monitor. Drop rows
    -- rather than scaling the case, which would desync the font from the grid.
    if h > sh * 0.95 then
        local overflow = h - math.floor(sh * 0.95)
        h = h - math.floor(overflow / self.lineH + 1) * self.lineH
    end

    self:setWidth(w)
    self:setHeight(h)
    self:layoutEntry()

    if not firstTime then
        local f = api("setZoom")
        if f then pcall(function() f(self.zoom) end) end
    end

    if firstTime then
        self:setX(math.floor((sw - w) / 2))
        self:setY(math.floor((sh - h) / 2))
    else
        self:setX(math.floor(math.max(0, math.min(sw - w, oldCX - w / 2))))
        self:setY(math.floor(math.max(0, math.min(sh - h, oldCY - h / 2))))
    end
end

--- Picks a starting zoom for the monitor. A method, not a local, so it
--- resolves through the metatable regardless of definition order.
--- On a 2000px-tall display the base font is a postage stamp.
function PZStoryBook:defaultZoom()
    local sh = getCore():getScreenHeight()
    if sh >= 1800 then return 4
    elseif sh >= 1300 then return 3
    elseif sh >= 900 then return 2 end
    return 1
end

function PZStoryBook:pause()
    local f = api("pauseOnOpen")
    if f then
        local want = true
        pcall(function() want = f() end)
        if want == false then return end
    end
    -- Copied from SpeedControlsHandler: remember the real multiplier, not just
    -- "it was running", so 2x/3x play resumes at the speed the player chose.
    if getGameSpeed() > 0 then
        self.prevSpeed = getGameTime():getTrueMultiplier()
        setGameSpeed(0)
    end
end

function PZStoryBook:resume()
    if self.prevSpeed ~= nil then
        setGameSpeed(1)
        getGameTime():setMultiplier(self.prevSpeed)
        self.prevSpeed = nil
    end
end

--- True when the JAR is older than this file needs. Checked once per open,
--- never per tick.
function PZStoryBook:checkFirmware()
    local v   = javaVersion()
    local api = javaApi()
    self.javaVer = v
    -- EXACT equality on the API version. A prefix match let "1.23.10" pass a
    -- requirement for "1.23.1"; a nil means the JAR predates apiVersion().
    self.mismatch = (api == nil) or (api ~= NEEDS_API)
    if self.mismatch then
        self.pageTitle = "FIRMWARE MISMATCH"
        self.pageText  =
            "The Pilot's software was replaced while the unit was still running.\n\n"
            .. "device firmware : " .. tostring(v)
            .. " (bridge " .. tostring(api or "too old") .. ")\n"
            .. "software expects: bridge " .. NEEDS_API .. "\n\n"
            .. "Loading a save reloads the interface but not the hardware layer. "
            .. "Quit Project Zomboid completely and start it again.\n\n"
            .. "Writing is disabled until then, so nothing is lost."
        self.statusLine = "restart required"
    end
    return not self.mismatch
end

function PZStoryBook:open()
    if self:checkFirmware() then
        -- A fresh campaign asks what kind of story it is before anything else.
        local f = api("scenario")
        if f then
            local cur = ""
            pcall(function() cur = f() or "" end)
            if cur == "" then self:openChooser() end
        end
        local f = api("timepiece")
        if f then pcall(function() self.timepiece = f() end) end
    end
    self.isOpen = true
    self:addToUIManager()
    self:setVisible(true)
    self:pause()
end

function PZStoryBook:close()
    if self.mode == "note" then self:closeNote() end
    self.isOpen = false
    self:resume()
    self:setVisible(false)
    self:removeFromUIManager()
end

-- ------------------------------------------------------------------- render

function PZStoryBook:prerender()
    local w, h = self:getWidth(), self:getHeight()
    local pad  = self.pad

    -- Case. Moulded plastic with a rounded shell: a light top plane, a mid
    -- front, a darker skirt, and a hard contour. Flat bands rather than a
    -- gradient - the engine has no gradient fill, and a hard seam reads as
    -- moulding anyway.
    local cr = math.max(4, math.floor(pad * 0.8))
    roundRect(self, 0, 0, w, h, cr, C.outline)
    roundRect(self, 1, 1, w - 2, h - 2, cr, C.front)
    local topH = math.max(2, math.floor(pad * 0.35))
    self:drawRect(cr, 1, w - cr * 2, topH, 1, C.rim[1], C.rim[2], C.rim[3])
    local skirt = math.floor(pad * 0.9)
    self:drawRect(cr, h - 1 - skirt, w - cr * 2, skirt, 1, C.side[1], C.side[2], C.side[3])

    -- Screen well, recessed with a dark lip and softened corners so it matches
    -- the mouldings on the keys.
    local sx, sy, sw, sh = self:screenRect()
    local r = math.max(2, math.floor(self.charW / 2))
    roundRect(self, sx - 5, sy - 5, sw + 10, sh + 10, r + 2, C.recess)
    roundRect(self, sx - 4, sy - 4, sw + 8, sh + 8, r + 2, C.outline, 0.6)
    roundRect(self, sx - 3, sy - 3, sw + 6, sh + 6, r + 1, C.recess)
    rgb(self, sx, sy, sw, sh, C.lcd)
    border(self, sx, sy, sw, sh, C.lcdEdge)

    -- The text box belongs to NOTE and TO DO and to nothing else. It is a real
    -- child widget, so it paints itself over the prose wherever it was last
    -- left - which is exactly what happened when HOME was pressed out of the
    -- to-do screen: a grey slab across the last two lines of every page.
    -- Hiding it per frame means no exit path can ever leak it again.
    if self.entry and self.mode ~= "note" and self.mode ~= "tasks" then
        if self.entry:isVisible() then
            self.entry:unfocus()
            self.entry:setVisible(false)
        end
    end

    if self.mode == "note" then
        self:renderNote(sx, sy, sw, sh)
    elseif self.mode == "setup" then
        self:renderSetup(sx, sy, sw, sh)
    elseif self.mode == "choose" then
        self:renderChooser(sx, sy, sw, sh)
    elseif self.mode == "tasks" then
        self:renderTasks(sx, sy, sw, sh)
    else
        self:renderScreen(sx, sy, sw, sh)
    end
    self:renderSoftKeys()
    self:renderChrome(w, h, sx, sy, sw, sh)
end

-- ------------------------------------------------------------ to-do list

--- The device's to-do list. Every PDA had one, and it is the honest place for
--- direction: the story PROPOSES an item, the player owns the list and can
--- strike anything out. A proposal on a list you control is not an order.
function PZStoryBook:openTasks()
    if self.mismatch then return end
    self.mode = "tasks"
    self:refreshTasks()
    if self.entry then
        self.entry:setVisible(true)
        self.entry:clear()
        self.entry:setPlaceholderText("something to do")
    end
    self.statusLine = "tap to tick off  ~ not now  x never"
end

function PZStoryBook:closeTasks()
    if self.entry then
        self.entry:unfocus()
        self.entry:setVisible(false)
        self.entry:setPlaceholderText("write what you are thinking")
    end
    self:home()
end

function PZStoryBook:refreshTasks()
    self.tasks = {}
    local f = api("todo")
    if f == nil then return end
    local raw
    if not pcall(function() raw = f() end) or raw == nil then return end
    local data = decodeObject(raw)
    if data == nil or type(data.todo) ~= "table" then return end
    for _, row in ipairs(data.todo) do
        if type(row) == "table" and type(row.text) == "string" and row.text ~= "" then
            table.insert(self.tasks, { done = row.done == true, later = row.later == true,
                src = type(row.source) == "string" and row.source or "player",
                text = row.text })
        end
    end
end

function PZStoryBook:addTask()
    local f = api("addTodo")
    if f == nil then return end
    local t = self.entry and self.entry:getText() or ""
    if t == nil or t:gsub("%s", "") == "" then
        self.statusLine = "nothing written"
        return
    end
    local ok = false
    pcall(function() ok = f(t) end)
    if ok then
        self.entry:clear()
        self:refreshTasks()
        self.statusLine = "added"
    else
        -- Keep the text so a transient disk failure can be retried.
        self.statusLine = "not added - duplicate, full, or not saved"
    end
end

function PZStoryBook:renderTasks(sx, sy, sw, sh)
    if self.tasks == nil then self:refreshTasks() end
    local x = sx + self.inset
    local y = sy + self.inset
    local innerW = sw - self.inset * 2
    local cols = math.max(16, math.floor(innerW / self.charW))

    self:drawText("things to do", x, y, C.ink[1], C.ink[2], C.ink[3], 1, self.font)
    y = y + self.lineH + 2
    rgb(self, x, y, innerW, 1, C.lcdEdge)
    y = y + 6

    local _, keysY = self:softKeys({ 1, 2, 3, 4 })
    local entryTop = keysY - 6 - (self.lineH * 2 + 8)
    if self.entry then
        self.entry:setY(entryTop)
        self.entry:setHeight(self.lineH * 2 + 8)
    end

    self.taskRows = {}
    -- Three states, three groups. Shelved sits between the live list and the
    -- finished one: still real, deliberately not urgent.
    local open, later, done = {}, {}, {}
    for i, t in ipairs(self.tasks or {}) do
        local into = t.done and done or (t.later and later or open)
        table.insert(into, { i = i, t = t })
    end

    -- Each row has two hit zones: the text ticks it, the "x" at the end
    -- strikes it out. They mean different things and the mod remembers both -
    -- done says it mattered, struck says never offer this again.
    -- An item WRAPS onto a second line rather than being cut off.
    --
    -- Truncation was the wrong answer here: "find something that runs, and the
    -- keys..." tells the player nothing they did not already know, and half
    -- the seeded list is longer than one line at this width. A to-do list
    -- exists to be read. Continuation lines hang under the text, clear of the
    -- checkbox, the way a paper list indents.
    --
    -- The old cut was also plain wrong: it reserved three columns, truncated
    -- with the ellipsis CODEPOINT (one character when measured), and asciify
    -- then expanded it to three ASCII dots afterwards - so every shortened row
    -- came out two characters over budget and drove its dots into the x.
    -- Two touch columns on the right: "~" shelves an item, "x" strikes it out.
    -- They mean completely different things - shelving teaches the narrator
    -- nothing, striking teaches it never to offer this again - so they cannot
    -- share a target.
    local xw = self.charW * 2
    local textW = innerW - xw * 2 - self.charW
    local MAXLINES = 2

    local function row(e, dim)
        local mark = e.t.done and "[x] " or "[ ] "
        local pad  = string.rep(" ", #mark)
        local body = asciify((e.t.src == "story" and "" or "* ") .. e.t.text)

        local width = math.max(8, math.floor(textW / self.charW) - #mark)
        local segs = wrap(body, width)
        if #segs > MAXLINES then
            -- Only a very long item reaches here. Keep what fits and mark the
            -- last kept line, because something really was dropped.
            local keep = {}
            for i = 1, MAXLINES do keep[i] = segs[i] end
            keep[MAXLINES] = keep[MAXLINES]:sub(1, math.max(4, width - 3)) .. "..."
            segs = keep
        end

        local need = #segs * self.lineH
        if y + need > entryTop - 6 then return false end

        local c = dim and C.inkDim or C.ink
        local top = y
        for i, seg in ipairs(segs) do
            self:drawText((i == 1 and mark or pad) .. seg, x, y,
                c[1], c[2], c[3], dim and 0.7 or 1, self.font)
            y = y + self.lineH
        end
        -- Both controls sit on the item's FIRST line, level with the checkbox.
        self:drawTextRight("x", x + innerW, top,
            C.inkDim[1], C.inkDim[2], C.inkDim[3], 0.8, self.font)
        self:drawTextRight(e.t.later and "^" or "~", x + innerW - xw, top,
            C.inkDim[1], C.inkDim[2], C.inkDim[3], 0.8, self.font)

        -- The whole text block ticks it off; the two columns do their own jobs.
        table.insert(self.taskRows,
            { x = x, y = top, w = innerW - xw * 2, h = need, idx = e.i })
        table.insert(self.taskRows,
            { x = x + innerW - xw * 2, y = top, w = xw, h = self.lineH,
              idx = e.i, later = true })
        table.insert(self.taskRows,
            { x = x + innerW - xw, y = top, w = xw, h = self.lineH,
              idx = e.i, drop = true })
        return true
    end

    if #open == 0 and #done == 0 then
        self:drawText("(nothing yet)", x, y, C.inkDim[1], C.inkDim[2], C.inkDim[3], 0.8, self.font)
        y = y + self.lineH
    end
    -- Nothing is allowed to fall off the bottom in silence. A list that
    -- quietly stops at the screen edge reads as a complete list.
    local shown, total = 0, #open + #later + #done
    for _, e in ipairs(open) do
        if not row(e, false) then break end
        shown = shown + 1
    end
    if #later > 0 then
        y = y + 4
        if y + self.lineH <= entryTop - 6 then
            self:drawText("not now", x, y, C.inkDim[1], C.inkDim[2], C.inkDim[3], 0.7, self.font)
            y = y + self.lineH
        end
        for _, e in ipairs(later) do
            if not row(e, true) then break end
            shown = shown + 1
        end
    end
    if #done > 0 then y = y + 4 end
    for _, e in ipairs(done) do
        if not row(e, true) then break end
        shown = shown + 1
    end
    if shown < total then
        -- On the last usable line, not at y - the loop stopped precisely
        -- because y had run out of room.
        self:drawText("+ " .. (total - shown) .. " more, off the bottom",
            x, entryTop - 6 - self.lineH,
            C.inkDim[1], C.inkDim[2], C.inkDim[3], 0.8, self.font)
    end

    local stripY = sy + sh - self.inset - self.lineH
    self:drawText(self:fit(self.statusLine, innerW), x, stripY,
        C.inkDim[1], C.inkDim[2], C.inkDim[3], 1, self.font)
end

-- ------------------------------------------------------------ story kind

--- The first thing a fresh campaign asks. Picked once, kept for the save.
function PZStoryBook:openChooser()
    self.mode = "choose"
    self.kinds = {}
    local f = api("scenarios")
    if f == nil then return end
    local raw
    if not pcall(function() raw = f() end) or raw == nil then return end
    local data = decodeObject(raw)
    if data == nil or type(data.scenarios) ~= "table" then return end
    for _, row in ipairs(data.scenarios) do
        if type(row) == "table" and type(row.id) == "string" then
            table.insert(self.kinds,
                { id = row.id,
                  key = type(row.key) == "string" and row.key or "?",
                  name = type(row.name) == "string" and row.name or row.id,
                  pitch = type(row.pitch) == "string" and row.pitch or "" })
        end
    end
    self.statusLine = "pick the kind of story this will be"
end

function PZStoryBook:chooseKind(i)
    local k = self.kinds and self.kinds[i]
    if k == nil then return end
    local f = api("setScenario")
    if f == nil then return end
    local ok = false
    pcall(function() ok = f(k.id) end)
    if not ok then
        self.statusLine = "could not set that"
        return
    end
    self.mode = "page"
    self.statusLine = k.name .. " - press WRITE to begin"
end

function PZStoryBook:renderChooser(sx, sy, sw, sh)
    local x = sx + self.inset
    local y = sy + self.inset
    local innerW = sw - self.inset * 2
    local cols = math.max(16, math.floor(innerW / self.charW))

    local n = 0
    local cf = api("archiveCount")
    if cf then pcall(function() n = cf() or 0 end) end
    self:drawText(n > 0 and "change the kind of story?" or "what kind of story is this?", x, y,
        C.ink[1], C.ink[2], C.ink[3], 1, self.font)
    y = y + self.lineH + 2
    rgb(self, x, y, innerW, 1, C.lcdEdge)
    y = y + 8

    self.kindRows = {}
    for i, k in ipairs(self.kinds or {}) do
        self:drawText(i .. ". " .. k.name, x, y, C.ink[1], C.ink[2], C.ink[3], 1, self.font)
        table.insert(self.kindRows, { x = x, y = y, w = innerW, h = self.lineH * 2, idx = i })
        y = y + self.lineH
        for _, line in ipairs(wrap(k.pitch, cols - 3)) do
            self:drawText("   " .. line, x, y,
                C.inkDim[1], C.inkDim[2], C.inkDim[3], 1, self.font)
            y = y + self.lineH
        end
        y = y + 6
    end

    y = y + self.lineH
    local note = "This shapes what the story notices, not what you have to do."
    if n > 0 then
        note = "You have " .. n .. " page(s) written. Those stay as they are; "
            .. "only what comes next will change. Press BACK to keep what you have."
    end
    for _, line in ipairs(wrap(note, cols)) do
        self:drawText(line, x, y, C.inkDim[1], C.inkDim[2], C.inkDim[3], 0.85, self.font)
        y = y + self.lineH
    end

    local stripY = sy + sh - self.inset - self.lineH
    self:drawText(self:fit(self.statusLine, innerW), x, stripY,
        C.inkDim[1], C.inkDim[2], C.inkDim[3], 1, self.font)
end

-- -------------------------------------------------------------- setup mode

--- The device's own settings screen.
---
--- B42 has no native mod-options API and the popular one is a workshop mod.
--- PZStory already asks the player to install ZombieBuddy; a second required
--- framework before publishing is a worse trade than owning this screen. It is
--- also more honest to the object - a 1993 handheld had a setup page.
local SETUP_ROWS = {
    { key = "knowledge", label = "what the story sees",
      values = { 1, 2, 3 },
      names  = { "just me", "a glance", "glance + memory" },
      hint   = "how much of the room the narrator may use" },
    { key = "words", label = "page length",
      values = { 150, 200, 300 },
      names  = { "short", "normal", "long" },
      hint   = "roughly how many words a page runs to" },
    { key = "nudge", label = "where to go next",
      values = { 1, 2, 3 },
      names  = { "never says", "a hint", "says it plainly" },
      hint   = "how hard a page pushes toward the next thing" },
    { key = "doom", label = "how it ends",
      values = { 1, 2, 3 },
      names  = { "might be won", "nobody knows", "this is how you died" },
      hint   = "the weight the story carries. never a spoiler" },
    { key = "pause", label = "pause the world",
      values = { true, false },
      names  = { "yes", "no" },
      hint   = "freeze the game while the device is open" },
}

function PZStoryBook:openSetup()
    if self.mismatch then return end
    self.mode = "setup"
    self:refreshSettings()
    self.statusLine = "tap a line to change it"
end

function PZStoryBook:refreshSettings()
    self.cfg = { knowledge = 3, words = 200, nudge = 2, doom = 3, pause = true,
                 profile = "?", model = "" }
    local f = api("settings")
    if f == nil then return end
    local raw
    if not pcall(function() raw = f() end) or raw == nil then return end
    local data = decodeObject(raw)
    if data == nil then return end
    self.cfg.knowledge = tonumber(data.knowledge) or 3
    self.cfg.words     = tonumber(data.words) or 200
    self.cfg.pause     = data.pause == true
    self.cfg.nudge     = tonumber(data.nudge) or 2
    self.cfg.doom      = tonumber(data.doom) or 3
    self.cfg.profile   = type(data.profile) == "string" and data.profile or "?"
    self.cfg.model     = type(data.model) == "string" and data.model or ""
end

--- Cycles one setting to its next value and writes it through immediately.
function PZStoryBook:cycleSetting(row)
    local r = SETUP_ROWS[row]
    if r == nil then return end
    local cur = self.cfg[r.key]
    local idx = 1
    for i, v in ipairs(r.values) do
        if v == cur then idx = i break end
    end
    local nextV = r.values[(idx % #r.values) + 1]

    local fn
    if r.key == "knowledge" then fn = api("setKnowledge")
    elseif r.key == "words"  then fn = api("setWords")
    elseif r.key == "nudge"  then fn = api("setNudge")
    elseif r.key == "doom"   then fn = api("setDoom")
    elseif r.key == "pause"  then fn = api("setPause") end
    if fn == nil then return end
    pcall(function() fn(nextV) end)
    self:refreshSettings()
    self.statusLine = r.hint
end

function PZStoryBook:renderSetup(sx, sy, sw, sh)
    if self.cfg == nil then self:refreshSettings() end
    local x = sx + self.inset
    local y = sy + self.inset
    local innerW = sw - self.inset * 2

    self:drawText("setup", x, y, C.ink[1], C.ink[2], C.ink[3], 1, self.font)
    y = y + self.lineH + 2
    rgb(self, x, y, innerW, 1, C.lcdEdge)
    y = y + 6

    self.setupRows = {}
    for i, r in ipairs(SETUP_ROWS) do
        local cur = self.cfg[r.key]
        local name = "?"
        for k, v in ipairs(r.values) do
            if v == cur then name = r.names[k] break end
        end
        self:drawText(r.label, x, y, C.ink[1], C.ink[2], C.ink[3], 1, self.font)
        self:drawTextRight("[ " .. name .. " ]", sx + sw - self.inset, y,
            C.ink[1], C.ink[2], C.ink[3], 1, self.font)
        table.insert(self.setupRows, { x = x, y = y, w = innerW, h = self.lineH, idx = i })
        y = y + self.lineH + 4
    end

    y = y + self.lineH
    rgb(self, x, y - 6, innerW, 1, C.lcdEdge)
    -- Tappable, so comparing providers does not mean editing JSON between
    -- runs. The key itself never appears - only the profile it belongs to.
    self:drawText("writing with", x, y, C.ink[1], C.ink[2], C.ink[3], 1, self.font)
    self:drawTextRight("[ " .. tostring(self.cfg.profile) .. " ]", sx + sw - self.inset, y,
        C.ink[1], C.ink[2], C.ink[3], 1, self.font)
    self.profileRow = { x = x, y = y, w = innerW, h = self.lineH }
    y = y + self.lineH
    self:drawTextRight(tostring(self.cfg.model), sx + sw - self.inset, y,
        C.inkDim[1], C.inkDim[2], C.inkDim[3], 0.8, self.font)
    y = y + self.lineH * 2
    self:drawText("keys and models are set in", x, y,
        C.inkDim[1], C.inkDim[2], C.inkDim[3], 0.8, self.font)
    y = y + self.lineH
    self:drawText("Zomboid/pzstory/profiles.json", x, y,
        C.inkDim[1], C.inkDim[2], C.inkDim[3], 0.8, self.font)

    local stripY = sy + sh - self.inset - self.lineH
    self:drawText(self:fit(self.statusLine, innerW), x, stripY,
        C.inkDim[1], C.inkDim[2], C.inkDim[3], 1, self.font)
end

-- --------------------------------------------------------------- note mode

function PZStoryBook:openNote()
    if self.mismatch then return end
    if self.streaming then
        self.statusLine = "stop or finish this page before editing notes"
        return
    end
    self.mode = "note"
    self:refreshStanding()
    if self.entry then
        self.entry:setVisible(true)
        self.entry:clear()
        self.entry:focus()
    end
    self.statusLine = "pick a kind, write, then SAVE"
end

function PZStoryBook:closeNote()
    self.mode = "page"
    if self.entry then
        self.entry:unfocus()
        self.entry:setVisible(false)
    end
    self.statusLine = "ready"
end

function PZStoryBook:refreshStanding()
    self.standing = {}
    local f = api("notes")
    if f == nil then return end
    local raw
    if not pcall(function() raw = f() end) or raw == nil then return end
    local data = decodeObject(raw)
    if data == nil or type(data.standing) ~= "table" then return end
    for _, text in ipairs(data.standing) do
        if type(text) == "string" then table.insert(self.standing, text) end
    end
end

function PZStoryBook:saveNote()
    local f = api("addNote")
    if f == nil then self:checkFirmware(); return end
    local text = self.entry and self.entry:getText() or ""
    if text == nil or text:gsub("%s", "") == "" then
        self.statusLine = "nothing written"
        return
    end
    local kind   = NOTE_TYPES[self.noteType]
    local result
    if not pcall(function() result = f(kind.id, text) end) then
        self.statusLine = "could not keep that note"
        return
    end

    local accepted = result == "kept as canon"
                  or result == "already kept as canon"
                  or result == "will steer the next page"
                  or result == "in force until you remove it"
    if not accepted then
        self.statusLine = tostring(result or "could not keep that note")
        return
    end

    self.entry:clear()
    self:refreshStanding()

    if kind.answer then
        -- An observation or a decision is an event. Close the pad, go back to
        -- the page, and let the story take it up straight away - the whole
        -- reason for writing it was to see it land.
        self:closeNote()
        -- addNote() has already put this observation/direction into the exact
        -- campaign prompt channel. Passing the same text again made the model
        -- receive it twice, once as a note and once as a fresh message.
        self:writePage()
    else
        -- A dial. Confirm it and stay put; it takes effect from the next page.
        self.statusLine = tostring(result or "kept")
    end
end

function PZStoryBook:renderNote(sx, sy, sw, sh)
    local x = sx + self.inset
    local y = sy + self.inset
    local innerW = sw - self.inset * 2

    self:drawText("note to myself", x, y, C.ink[1], C.ink[2], C.ink[3], 1, self.font)
    y = y + self.lineH + 2
    rgb(self, x, y, innerW, 1, C.lcdEdge)
    y = y + 4

    -- What the chosen kind will actually do. The lifetime is the decision.
    self:drawText(NOTE_TYPES[self.noteType].blurb, x, y,
        C.inkDim[1], C.inkDim[2], C.inkDim[3], 1, self.font)

    -- The text box is a real child widget and draws itself; below it goes the
    -- list of standing instructions currently in force. Without seeing that
    -- list the player can never tell what they are still under.
    local listY = (self.entry and (self.entry:getY() + self.entry:getHeight()) or y) + 6
    rgb(self, x, listY - 4, innerW, 1, C.lcdEdge)
    self:drawText("things I've told myself  (tap one to drop it)", x, listY,
        C.inkDim[1], C.inkDim[2], C.inkDim[3], 1, self.font)
    listY = listY + self.lineH

    self.standingRows = {}
    local cols = math.max(16, math.floor(innerW / self.charW))
    local _, keysY = self:softKeys({ 1, 2, 3, 4, 5 })
    local bottom = keysY - 6
    if #self.standing == 0 then
        self:drawText("(nothing)", x, listY, C.inkDim[1], C.inkDim[2], C.inkDim[3], 0.8, self.font)
    end
    for i, s in ipairs(self.standing) do
        if listY + self.lineH > bottom then break end
        local line = self:fit(i .. ". " .. s, innerW)
        self:drawText(line, x, listY, C.ink[1], C.ink[2], C.ink[3], 1, self.font)
        table.insert(self.standingRows,
            { x = x, y = listY, w = innerW, h = self.lineH, idx = i })
        listY = listY + self.lineH
    end

    local stripY = sy + sh - self.inset - self.lineH
    self:drawText(self:fit(self.statusLine, innerW), x, stripY,
        C.inkDim[1], C.inkDim[2], C.inkDim[3], 1, self.font)
end

function PZStoryBook:renderScreen(sx, sy, sw, sh)
    local x = sx + self.inset
    local y = sy + self.inset
    local innerW = sw - self.inset * 2
    local cols = math.max(16, math.floor(innerW / self.charW))

    -- Header. The clock obeys the world: no watch, no time; only a digital
    -- watch carries a date.
    local head = self.pageTitle ~= "" and self.pageTitle:upper() or "NO PAGE"
    self:drawText(head, x, y, C.ink[1], C.ink[2], C.ink[3], 1, self.font)
    local stamp = self:stamp()
    if stamp ~= "" then
        self:drawTextRight(stamp, sx + sw - self.inset, y,
            C.inkDim[1], C.inkDim[2], C.inkDim[3], 1, self.font)
    end
    y = y + self.lineH + 2
    rgb(self, x, y, innerW, 1, C.lcdEdge)
    y = y + 6

    local bodyTop    = y
    local _, keysY   = self:softKeys({ 1 })
    local bodyBottom = keysY - 6
    local visible    = math.max(1, math.floor((bodyBottom - bodyTop) / self.lineH))

    local lines = wrap(self.pageText, cols)
    self.maxScroll = math.max(0, #lines - visible)
    if self.scroll > self.maxScroll then self.scroll = self.maxScroll end

    -- Belt and braces: even if a wrap calculation is ever wrong again, the
    -- stencil guarantees no glyph can be painted outside the screen well.
    self:setStencilRect(sx + 1, sy + 1, sw - 2, sh - 2)
    for i = 1, visible do
        local line = lines[i + self.scroll]
        if line == nil then break end
        self:drawText(line, x, bodyTop + (i - 1) * self.lineH,
            C.ink[1], C.ink[2], C.ink[3], 1, self.font)
    end
    self:clearStencilRect()

    -- Blinking caret while the model is still writing: the clearest possible
    -- signal that the page is alive rather than stuck.
    if self.streaming and (getTimestampMs() % 1000) < 500 then
        local caretRow = math.min(#lines - self.scroll, visible)
        if caretRow >= 1 then
            local last = lines[#lines] or ""
            self:drawText("_", x + #last * self.charW,
                bodyTop + (caretRow - 1) * self.lineH,
                C.ink[1], C.ink[2], C.ink[3], 1, self.font)
        end
    end

    -- The status strip shares its line with the page counter, so it must be
    -- cut to fit. Drawing both at full length put "1/9" through the middle of
    -- the last word - the counter is short and fixed, the status is long and
    -- arbitrary, so the status is the one that yields.
    local stripY = sy + sh - self.inset - self.lineH
    local counter = self.maxScroll > 0
        and string.format("%d/%d", self.scroll + 1, self.maxScroll + 1) or ""
    local room = innerW
    if counter ~= "" then
        room = room - getTextManager():MeasureStringX(self.font, counter)
                    - self.charW * 2
    end
    self:drawText(self:fit(self.statusLine, room), x, stripY,
        C.inkDim[1], C.inkDim[2], C.inkDim[3], 1, self.font)
    if counter ~= "" then
        self:drawTextRight(counter, sx + sw - self.inset, stripY,
            C.inkDim[1], C.inkDim[2], C.inkDim[3], 1, self.font)
    end
end

--- Truncates a string to a pixel width, with an ellipsis if it had to cut.
function PZStoryBook:fit(text, pxWide)
    local s = asciify(text or "")
    if pxWide <= 0 then return "" end
    local cols = math.floor(pxWide / self.charW)
    if cols < 4 then return "" end
    if #s <= cols then return s end
    return s:sub(1, cols - 3) .. "..."
end

--- Time and date, but only what the survivor could actually read off his wrist.
function PZStoryBook:stamp()
    local gt = getGameTime()
    if gt == nil or self.timepiece == "none" then return "" end
    local t = string.format("%02d:%02d", gt:getHour(), gt:getMinutes())
    if self.timepiece == "digital" then
        local months = {"JAN","FEB","MAR","APR","MAY","JUN",
                        "JUL","AUG","SEP","OCT","NOV","DEC"}
        return string.format("%02d %s  %s",
            gt:getDay() + 1, months[(gt:getMonth() % 12) + 1], t)
    end
    return t
end

function PZStoryBook:renderChrome(w, h, sx, sy, sw, sh)
    -- POWER. A real button on the case, top left, lit green while the device
    -- is on - not a soft key. Switching a machine off is the one action that
    -- must never live on the screen it is switching off, and a 1993 handheld
    -- would have put it exactly here.
    local d  = self.powerD or (self.lineH + 4)
    local px = sx
    local py = self.pad + math.floor((self.nameH - d) / 2)
    local pr = math.floor(d / 2)
    local down = self.powerPressed == true

    local cx = px + pr
    local cy = py + pr + (down and 1 or 0)
    -- Moulded bezel, then the lens, then the brighter core it is lit from.
    disc(self, cx, cy - (down and 1 or 0), pr + 2, C.recess)
    disc(self, cx, cy - (down and 1 or 0), pr + 1, C.outline, 0.7)
    disc(self, cx, cy, pr, down and C.powerDark or C.power)
    disc(self, cx, cy, math.max(1, pr - math.max(2, math.floor(d / 6))),
         down and C.power or C.powerLit)
    if not down then
        -- A catch of light on the upper-left of the curve.
        self:drawRect(cx - math.floor(pr / 2), cy - pr + 2,
                      math.max(2, math.floor(pr / 2)), 1, 0.5, 1, 1, 1)
    end
    self.powerButton = { x = px, y = py, w = d, h = d }

    -- Nameplate, cut into the plastic above the screen: product name left of
    -- centre, maker right, the way a 1993 handheld wore them.
    local nameY = self.pad + math.floor((self.nameH - self.lineH) / 2)
    engrave(self, MODEL, px + d + math.floor(self.charW), nameY, self.font, 1.0)
    local bw2 = getTextManager():MeasureStringX(self.font, BRAND)
    engrave(self, BRAND, sx + sw - bw2, nameY, self.font, 0.9)

    -- Power light and zoom rocker live on the skirt below the screen.
    local strip = sy + sh + math.floor(self.lineH * 0.4)

    -- The activity light. A disc now, so it matches the power lens rather
    -- than reading as a stray square jammed against the bezel corner.
    local dot = math.max(3, math.floor(self.charW * 0.4))
    local dcx = sx + sw - dot - 2
    local dcy = strip + 3 + dot
    disc(self, dcx, dcy, dot + 1, C.outline, 0.7)
    disc(self, dcx, dcy, dot, self.streaming and C.led or C.recess)

    -- Zoom rocker, sitting where a volume switch would.
    local zw = self.charW * 3
    local zy = strip
    self.zoomButtons = {}
    local zx = (dcx - dot) - zw * 2 - 12
    local zr = math.max(2, math.floor(self.charW / 3))
    for i, lab in ipairs({ "-", "+" }) do
        local bx = zx + (i - 1) * (zw + 4)
        roundRect(self, bx - 1, zy - 1, zw + 2, self.lineH + 2, zr + 1, C.recess)
        roundRect(self, bx, zy, zw, self.lineH, zr, C.front)
        self:drawRect(bx + zr, zy, zw - zr * 2, 1, 0.45, C.rim[1], C.rim[2], C.rim[3])
        local tw = getTextManager():MeasureStringX(self.font, lab)
        engrave(self, lab, bx + (zw - tw) / 2, zy, self.font, 1.0)
        table.insert(self.zoomButtons, { x = bx, y = zy, w = zw, h = self.lineH, dir = (i == 1) and -1 or 1 })
    end
    self:drawTextRight(ZOOM[self.zoom].label, zx - 6, zy,
        C.recess[1], C.recess[2], C.recess[3], 0.8, self.font)

    self:renderHardwareKeys(w, h, sx, sw)

end

--- The fixed hardware row: page back, back, home, page forward.
---
--- These are moulded into the case and NEVER change what they say, which is
--- the whole reason they are allowed to be physical. Every 1993 handheld had
--- exactly this set below the glass.
local HW_KEYS = { "<", "BACK", "HOME", ">" }

function PZStoryBook:renderHardwareKeys(w, h, sx, sw)
    local y  = h - self.pad - self.hwH + 6
    local bh = self.hwH - 12
    local r  = math.max(2, math.floor(self.charW / 3))

    -- The pair of page keys is narrow; BACK and HOME are wide. That weighting
    -- is what the real devices did, and it makes them findable without looking.
    local gap  = math.max(4, math.floor(self.charW / 2))
    local nar  = math.floor(self.charW * 3.2)
    local wide = math.floor((sw - nar * 2 - gap * 3) / 2)
    local widths = { nar, wide, wide, nar }

    self.hwButtons = {}
    local bx = sx
    for i, label in ipairs(HW_KEYS) do
        local bw = widths[i]
        local down = (self.hwPressed == i)

        roundRect(self, bx - 2, y - 2, bw + 4, bh + 4, r + 1, C.recess)
        roundRect(self, bx - 1, y - 1, bw + 2, bh + 2, r + 1, C.outline, 0.55)
        roundRect(self, bx, y + (down and 1 or 0), bw, bh - (down and 1 or 0), r,
                  down and C.side or C.front)
        if not down then
            self:drawRect(bx + r, y, bw - r * 2, 1, 0.5, C.rim[1], C.rim[2], C.rim[3])
        end

        local tw = getTextManager():MeasureStringX(self.font, label)
        engrave(self, label, bx + (bw - tw) / 2, y + 3 + (down and 1 or 0),
                self.font, down and 0.75 or 1.0)
        table.insert(self.hwButtons, { x = bx, y = y, w = bw, h = bh, id = i })
        bx = bx + bw + gap
    end
end

function PZStoryBook:hardware(id)
    if self.mode == "choose" then return end
    if id == 1 then self:step(-1)
    elseif id == 4 then self:step(1)
    elseif id == 2 then
        -- BACK: up one level. Out of the pad, or off an archive page.
        if self.mode == "choose" then
            -- Only a trap while no kind is set; otherwise BACK leaves it alone.
            local f, cur = api("scenario"), ""
            if f then pcall(function() cur = f() or "" end) end
            if cur ~= "" then self:home() end
            return
        elseif self.mode == "note" then self:closeNote()
        elseif self.mode == "setup" then self:home()
        elseif self.mode == "tasks" then self:closeTasks()
        elseif self.viewing ~= nil then self:home()
        else
            -- Top level: BACK reopens the chooser ONLY before the first page.
            -- A mis-tap should be recoverable; a story already begun should
            -- not be able to change what kind of story it is.
            local cf, n = api("archiveCount"), 0
            if cf then pcall(function() n = cf() or 0 end) end
            if n == 0 then
                self:openChooser()
            else
                self.statusLine = "the story has begun - this is what it is now"
            end
        end
    elseif id == 3 then
        self:home()
    end
end

--- HOME: whatever the device is doing, return to the page on the screen now.
function PZStoryBook:home()
    if self.mode == "choose" then return end   -- must pick first
    if self.mode == "note" then self:closeNote() end
    self.mode = "page"
    self.viewing = nil
    self.scroll  = 0
    self:refreshLive()
    if self.pageText == nil or self.pageText == "" then
        self.statusLine = "no page yet - press WRITE"
    else
        self.statusLine = "current page"
    end
end

--- Draws the on-screen keys. Monochrome LCD convention: outlined when idle,
--- inverted - filled ink with pale glyphs - when pressed or selected. That
--- inversion is exactly how these displays showed a touch.
function PZStoryBook:renderSoftKeys()
    -- Only what CHANGES lives on the glass. Navigation is moulded into the
    -- case below, where it never relabels itself.
    local labels
    if self.mode == "note" then
        -- The action key states what it will actually do. Legitimate here in a
        -- way it never was on a moulded key: this is glass.
        local act = NOTE_TYPES[self.noteType].answer and "KEEP+WRITE" or "KEEP"
        labels = { NOTE_TYPES[1].label, NOTE_TYPES[2].label, NOTE_TYPES[3].label, act }
    elseif self.mode == "choose" then
        labels = {}
        for _, k in ipairs(self.kinds or {}) do table.insert(labels, k.key) end
    elseif self.mode == "tasks" then
        labels = { "ADD", "CLEAR DONE", "BACK" }
    elseif self.mode == "setup" then
        labels = { "DONE" }
    else
        if self.streaming then
            labels = { "STOP" }
        else
        -- No CLOSE. Switching the device off is the power button on the case,
        -- where it cannot be hit by accident while reading.
        -- While a provider hold is running the first key counts it down, so
        -- the wait is visible on the key the player is reaching for.
        local held = self:heldFor()
        labels = { held > 0 and ("WAIT " .. held) or "WRITE",
                   "NOTE", "TO DO", "SETUP" }
        end
    end

    if #labels == 0 then self.buttons = {} return end
    local keys = self:softKeys(labels)
    self.buttons = keys
    for i, b in ipairs(keys) do
        local on = (self.pressed == i)
                or (self.mode == "note" and i == self.noteType)
        if on then
            rgb(self, b.x, b.y, b.w, b.h, C.ink)
        else
            border(self, b.x, b.y, b.w, b.h, C.ink, 0.75)
        end
        local label = labels[i]
        local tw = getTextManager():MeasureStringX(self.font, label)
        local c  = on and C.lcd or C.ink
        self:drawText(label, b.x + (b.w - tw) / 2, b.y + 3, c[1], c[2], c[3], 1, self.font)
    end
end

-- -------------------------------------------------------------------- input

local function hit(b, x, y)
    return x >= b.x and x <= b.x + b.w and y >= b.y and y <= b.y + b.h
end

function PZStoryBook:onMouseDown(x, y)
    if self.mode == "choose" then
        -- The soft keys below are the control surface; tapping a line still
        -- works, but the buttons are what the other screens taught you.
        for _, r in ipairs(self.kindRows or {}) do
            if hit(r, x, y) then self:chooseKind(r.idx); return true end
        end
    end
    if self.mode == "tasks" then
        for _, r in ipairs(self.taskRows or {}) do
            if hit(r, x, y) then
                local which = r.drop and "dropTodo"
                            or (r.later and "laterTodo" or "toggleTodo")
                local f = api(which)
                local kept = false
                if f then pcall(function() kept = f(r.idx) == true end) end
                if not kept then
                    self.statusLine = "could not save that change"
                    return true
                end
                self:refreshTasks()
                self.statusLine = r.drop and "struck off - it will not come back"
                    or (r.later and "shelved - kept, but not chased"
                                 or "ticked off")
                return true
            end
        end
    end
    if self.mode == "setup" then
        for _, r in ipairs(self.setupRows or {}) do
            if hit(r, x, y) then self:cycleSetting(r.idx); return true end
        end
        if self.profileRow and hit(self.profileRow, x, y) then
            local f = api("nextProfile")
            if f then
                local name
                pcall(function() name = f() end)
                self:refreshSettings()
                -- A hold belongs to the provider that imposed it. Switching to
                -- a different service must not leave the player watching a
                -- countdown for a rate limit that has nothing to do with it.
                self.retryAt = nil
                self.statusLine = "now writing with " .. tostring(name or "?")
            end
            return true
        end
    end
    if self.mode == "note" then
        for _, r in ipairs(self.standingRows or {}) do
            if hit(r, x, y) then
                local f = api("removeStanding")
                if f then
                    local removed = false
                    pcall(function() removed = f(r.idx) == true end)
                    if removed then
                        self:refreshStanding()
                        self.statusLine = "cancelled"
                    else
                        self.statusLine = "could not save that change"
                    end
                end
                return true
            end
        end
    end
    if self.powerButton and hit(self.powerButton, x, y) then
        self.powerPressed = true
        return true
    end
    for _, b in ipairs(self.zoomButtons or {}) do
        if hit(b, x, y) then
            self.zoom = math.max(1, math.min(#ZOOM, self.zoom + b.dir))
            self:applyZoom(false)
            return true
        end
    end
    for _, b in ipairs(self.buttons or {}) do
        if hit(b, x, y) then self.pressed = b.id; return true end
    end
    for _, b in ipairs(self.hwButtons or {}) do
        if hit(b, x, y) then self.hwPressed = b.id; return true end
    end
    return ISPanel.onMouseDown(self, x, y)
end

function PZStoryBook:onMouseUp(x, y)
    local was, hwWas = self.pressed, self.hwPressed
    local pwrWas = self.powerPressed
    self.pressed, self.hwPressed, self.powerPressed = nil, nil, nil

    if pwrWas and self.powerButton and hit(self.powerButton, x, y) then
        self:close()
        return true
    end

    for _, b in ipairs(self.hwButtons or {}) do
        if b.id == hwWas and hit(b, x, y) then
            self:hardware(b.id)
            return true
        end
    end

    for _, b in ipairs(self.buttons or {}) do
        if b.id == was and hit(b, x, y) then
            if self.mode == "choose" then
                self:chooseKind(b.id)
            elseif self.mode == "note" then
                if b.id <= 3 then
                    self.noteType = b.id
                    self.statusLine = NOTE_TYPES[b.id].blurb
                elseif b.id == 4 then self:saveNote() end
            elseif self.mode == "tasks" then
                if b.id == 1 then self:addTask()
                elseif b.id == 2 then
                    local f = api("clearDoneTodo")
                    local cleared = false
                    if f then pcall(function() cleared = f() == true end) end
                    if cleared then
                        self:refreshTasks()
                        self.statusLine = "cleared"
                    else
                        self.statusLine = "could not save that change"
                    end
                elseif b.id == 3 then self:closeTasks() end
            elseif self.mode == "setup" then
                self:home()
            else
                if b.id == 1 and self.streaming then
                    local f = api("cancelPage")
                    if f then pcall(f) end
                    self.statusLine = "stopping..."
                elseif b.id == 1 then self:writePage()
                elseif b.id == 2 then self:openNote()
                elseif b.id == 3 then self:openTasks()
                elseif b.id == 4 then self:openSetup() end
            end
            return true
        end
    end
    return ISPanel.onMouseUp(self, x, y)
end

function PZStoryBook:onMouseWheel(del)
    self.scroll = math.max(0, math.min(self.maxScroll or 0, self.scroll + del))
    return true
end

-- ------------------------------------------------------------------ the page

--- @param freshNote optional: a note written seconds ago that this page should
---                  answer directly, rather than treating as background.
function PZStoryBook:writePage(freshNote)
    if self.streaming or self.mismatch then return end

    -- Honour a hold the provider asked for. Hammering WRITE into a service
    -- that has just refused is how a short rate limit becomes a long one.
    local held = self:heldFor()
    if held > 0 then
        self.statusLine = "the line is still busy - " .. held .. "s"
        return
    end

    local request = api("requestStoryPage")
    if request == nil then
        self:checkFirmware()
        return
    end

    self.statusLine = freshNote and "thinking about that..." or "reading the world..."
    local tp = api("timepiece")
    if tp then pcall(function() self.timepiece = tp() end) end

    local refusal
    local ok = pcall(function() refusal = request(freshNote or "") end)
    if not ok then
        self.statusLine = "the pen would not start"
        return
    end
    if refusal ~= nil then
        self.statusLine = tostring(refusal)
        return
    end
    -- Preserve the page already on screen when a request cannot even start.
    -- Once accepted, clear it before the next tick can drain streamed text.
    self.viewing = nil                    -- writing always returns to the live page
    self.pageTitle, self.pageText = "", ""
    self.scroll, self.chunks = 0, 0
    self.streaming  = true
    self.statusLine = "connecting..."
end

--- Walks the archive. Page 0 is the live page currently on the screen.
function PZStoryBook:step(dir)
    if self.streaming or self.mismatch then return end
    local countFn, pageFn = api("archiveCount"), api("archivePage")
    if countFn == nil or pageFn == nil then self:checkFirmware(); return end

    local count = 0
    pcall(function() count = countFn() end)
    if count == 0 then
        self.statusLine = "nothing written yet"
        return
    end

    local n = (self.viewing or count + 1) + dir

    -- Page zero is the campaign's reason for being. Paging back past the
    -- first chapter reaches it, which is where a reader would look for it.
    if n < 1 then
        local pf = api("premise")
        local why = ""
        if pf then pcall(function() why = pf() or "" end) end
        if why == "" then
            self.statusLine = "the first page will say why"
            return
        end
        self.viewing    = 0
        self.pageTitle  = "WHY THEY ARE DOING THIS"
        self.pageText   = why
        self.scroll     = 0
        self.statusLine = "the reason this story began"
        return
    end
    if n > count then
        -- Walking off the end returns to the page that is on the screen now.
        self.viewing = nil
        self.statusLine = "current page"
        self:refreshLive()
        return
    end

    local raw
    if not pcall(function() raw = pageFn(n) end) or raw == nil then return end
    local page = decodeObject(raw)
    if page == nil then
        self.statusLine = "could not read that stored page"
        return
    end
    self.viewing   = n
    self.pageTitle = type(page.title) == "string" and page.title or ""
    self.pageText  = type(page.text) == "string" and page.text or ""
    local stamp    = type(page.stamp) == "string" and page.stamp or ""
    self.scroll    = 0
    self.statusLine = string.format("page %d of %d   %s", n, count, stamp)
end

--- Pulls the live page straight from Java. The reply format is the mod's own
--- contract and parsing it twice - once here, once there - would drift.
function PZStoryBook:refreshLive()
    local t, b = api("pageTitle"), api("pageBody")
    if t == nil or b == nil then
        -- Old JAR. Stop rather than throwing on every tick.
        self.streaming = false
        self:checkFirmware()
        return
    end
    pcall(function()
        self.pageTitle = t() or ""
        self.pageText  = b() or ""
    end)
end

--- The device fails in character.
---
--- A rate limit is not a story event and must not pretend to be one - writing
--- "he held the pen and nothing came" for a 429 quietly tells the player their
--- survivor had a bad moment, when actually a server said no. This is the
--- MACHINE's fault, so the machine says so, in the voice of a 1993 handheld
--- that talks to something over a line. The exact HTTP text still goes to the
--- log and the status strip, so it stays diagnosable.
local FAULTS = {
    rate = { "THE LINE IS BUSY",
        "Too many pages, too quickly. Whatever is on the other end of this "
        .. "line has stopped taking them for a moment.\n\nIt is not the story. "
        .. "It is the line." },
    overload = { "NO ANSWER",
        "The line is open but nobody is picking up. Whatever writes these "
        .. "pages is busy elsewhere.\n\nTry again shortly." },
    auth = { "CREDENTIALS REFUSED",
        "The service will not accept this device's key.\n\nCheck the key for "
        .. "this profile in\nZomboid/pzstory/profiles.json - it is missing, "
        .. "mistyped, or has been revoked." },
    credit = { "ACCOUNT EMPTY",
        "The account behind this key has nothing left to spend.\n\nAdd credit "
        .. "with the provider, or switch to another profile in SETUP." },
    model = { "UNKNOWN CORRESPONDENT",
        "The service does not recognise the model this profile asks for.\n\n"
        .. "Check the model id in profiles.json." },
    network = { "NO CARRIER",
        "This device cannot reach the outside at all. No route, or something "
        .. "on this machine is blocking it.\n\nThe story is safe. Nothing has "
        .. "been lost." },
    timeout = { "THE LINE WENT DEAD",
        "Something answered and then stopped talking mid-sentence.\n\nThe page "
        .. "was not written. Nothing has been lost." },
    request = { "MALFORMED REQUEST",
        "The service rejected the shape of what this device sent.\n\nThis one "
        .. "is a fault in the device itself - the log has the detail." },
    invalid_output = { "UNREADABLE PAGE",
        "A complete reply came back, but it did not contain every required "
        .. "part of a safe page.\n\nIt was discarded. The archive, canon, list "
        .. "and last known state are unchanged." },
    save = { "STORAGE FAILURE",
        "The page was complete, but this device could not preserve the whole "
        .. "campaign transaction on disk.\n\nThe old campaign is unchanged. "
        .. "Check free space and folder permissions before trying again." },
    truncated = { "PAGE CUT OFF",
        "The reply ended before the page contract was complete.\n\nNothing was "
        .. "saved. Lower the page length or raise this profile's output cap." },
    too_large = { "REPLY TOO LARGE",
        "The reply crossed the device's hard safety limit and was discarded.\n\n"
        .. "Nothing was saved." },
    request_too_large = { "BOOK TOO LARGE",
        "The encoded request exceeded this profile's deliberate input limit.\n\n"
        .. "Reduce retained history or raise the profile limit knowingly." },
    endpoint = { "UNSAFE ADDRESS",
        "This profile points to an address the device will not send private "
        .. "story data to.\n\nUse HTTPS remotely, or a literal loopback address "
        .. "for a local model." },
    protocol = { "BROKEN TRANSMISSION",
        "The service answered, but the stream was not valid provider data.\n\n"
        .. "The unreadable reply was discarded and nothing was saved." },
}

function PZStoryBook:showFault(kind, waitSec, err)
    local f = FAULTS[kind] or { "NOTHING CAME",
        "The line did something this device has no word for.\n\nThe log has "
        .. "the detail. The story is safe." }

    self.pageTitle = f[1]
    local body = f[2]
    if waitSec and waitSec > 0 then
        self.retryAt = getTimestampMs() + waitSec * 1000
        body = body .. "\n\nIt has asked for " .. waitSec
                    .. " seconds. WRITE comes back on its own."
    else
        -- Nothing told us how long. A short hold still beats letting the
        -- player hammer WRITE into a provider that is already refusing.
        self.retryAt = (kind == "rate" or kind == "overload")
                       and (getTimestampMs() + 20000) or nil
    end
    self.pageText   = body
    self.scroll     = 0
    self.statusLine = "fault: " .. asciify(tostring(err)):sub(1, 60)
end

--- Seconds left on a provider-imposed hold, or 0.
function PZStoryBook:heldFor()
    if self.retryAt == nil then return 0 end
    local left = math.ceil((self.retryAt - getTimestampMs()) / 1000)
    if left <= 0 then self.retryAt = nil; return 0 end
    return left
end

function PZStoryBook:drain()
    if not self.streaming or self.mismatch then return end

    local poll = api("pollStream")
    if poll == nil then self.streaming = false; self:checkFirmware(); return end

    local raw
    if not pcall(function() raw = poll() end) or raw == nil then
        self.streaming = false
        self.statusLine = "lost contact with the pen"
        return
    end

    local data = decodeObject(raw)
    if data == nil then
        self.streaming = false
        self.statusLine = "the pen returned an unreadable status"
        return
    end
    local status  = data.status
    local done    = data.done == true
    local err     = data.error
    local chars   = tonumber(data.chars) or 0
    local elapsed = tonumber(data.elapsedMs) or 0
    local inTok   = data.inputTokens
    local outTok  = data.outputTokens
    local cRead   = tonumber(data.cacheRead) or 0
    local cWrite  = tonumber(data.cacheWrite) or 0
    local delta   = data.delta

    if delta and delta ~= "" then self.chunks = self.chunks + 1 end

    -- Pull the whole text rather than stitching deltas: unescaping happens
    -- once on a complete string instead of on every fragment.
    if chars > 0 then
        self:refreshLive()
        -- Follow the writing, unless the reader scrolled up to re-read.
        if self.scroll >= (self.maxScroll or 0) - 1 then
            self.scroll = self.maxScroll or 0
        end
    end

    if status == "CONNECTING" then
        self.statusLine = string.format("connecting... %.1fs", elapsed / 1000)
    elseif status == "STREAMING" then
        self.statusLine = string.format("writing  %d chars  %.1fs", chars, elapsed / 1000)
    elseif status == "RECEIVED" then
        self.statusLine = "checking the finished page..."
    elseif status == "COMMITTING" then
        self.statusLine = "saving the finished page..."
    end

    if done then
        self.streaming = false
        -- Back to the beginning. The view follows the writing while it
        -- streams, which is the right behaviour mid-flight and exactly the
        -- wrong one the moment it stops: a finished page is read from the
        -- top, and leaving it at the bottom reads as "the start got cut off".
        self.scroll = 0
        if status == "CANCELLED" then
            self.pageTitle  = "PAGE STOPPED"
            self.pageText   = "The unfinished reply was discarded. Nothing was saved to this story."
            self.statusLine = "stopped - nothing saved"
        elseif err then
            local kind  = type(data.failKind) == "string" and data.failKind or "unknown"
            local wait  = tonumber(data.retryAfter) or 0
            self:showFault(kind, wait, err)
        else
            -- Show what the cache did: "cached 4.2k" means that much of the
            -- book was re-read at a fraction of the price instead of resent.
            local cache = ""
            if cRead > 0 then
                cache = string.format("  cached %.1fk", cRead / 1000)
            elseif cWrite > 0 then
                cache = string.format("  stored %.1fk", cWrite / 1000)
            end
            self.statusLine = string.format("done  %.1fs  %s>%s tok%s",
                elapsed / 1000, tostring(inTok or "?"), tostring(outTok or "?"), cache)

            -- After the FIRST page, show the reason before the page itself.
            -- It is written in that same reply and is the answer to "why am I
            -- doing this" - making the player go looking for it is why it
            -- felt missing.
            local cf, n = api("archiveCount"), 0
            if cf then pcall(function() n = cf() or 0 end) end
            if n == 1 then
                local pf, why = api("premise"), ""
                if pf then pcall(function() why = pf() or "" end) end
                if why ~= "" then
                    self.viewing    = 0
                    self.pageTitle  = "WHY I AM DOING THIS"
                    self.pageText   = why
                    self.scroll     = 0
                    self.statusLine = "press > for the first page"
                end
            end
        end
    end
end

-- ------------------------------------------------------------------- wiring

local BOOK_BIND = "PZStory: open the book"
table.insert(keyBinding, { value = BOOK_BIND, key = Keyboard.KEY_F7 })

local instance = nil
local observer = nil
local nextObserverAt = 0

local function toggle()
    if getPlayer() == nil then return end
    if instance == nil then
        -- Construction must not fail silently: without this the key just does
        -- nothing forever and the only clue is buried in console.txt.
        local ok, err = pcall(function()
            instance = PZStoryBook:new()
            instance:initialise()
        end)
        if not ok then
            instance = nil
            print("[PZStory] could not build the device: " .. tostring(err))
            local pl = getPlayer()
            if pl then
                pcall(function()
                    HaloTextHelper.addBadText(pl, "PZStory: device failed to start")
                end)
            end
            return
        end
    end
    -- Our own flag, NOT getIsVisible().
    --
    -- ISUIElement:getIsVisible() instantiates the java object if it does not
    -- exist yet, and a fresh UIElement defaults to VISIBLE. So the very first
    -- F7 after the device was built asked "are you visible?", got true from an
    -- object that had never been on screen, and helpfully closed it. Every
    -- first press was swallowed and the device only opened on the second.
    if instance.isOpen then instance:close() else instance:open() end
end

Events.OnKeyPressed.Add(function(key)
    if key == getCore():getKey(BOOK_BIND) then toggle() end
end)

-- What the character has actually SEEN. The engine fires this the moment a
-- room comes into view, which is exactly the line Elkin drew: a glance at the
-- room he is in, plus memory of the rooms he has walked through. Anywhere he
-- has not been stays unknown to the narrator.
Events.OnSeeNewRoom.Add(function(room)
    if room == nil then return end
    local f = api("sawRoom")
    if f == nil then return end
    pcall(function()
        local name = room.getName and room:getName() or nil
        local b = room.getBuilding and room:getBuilding() or nil
        local bid = nil
        if b ~= nil and b.getID then bid = tostring(b:getID()) end
        if name ~= nil then f(tostring(name), bid) end
    end)
end)

-- Polling remains the correctness baseline, but these supported Build 42
-- callbacks close the five-second blind spot around short-lived transitions.
-- They merely request an immediate factual snapshot; no callback fabricates a
-- story event and none can contact a provider.
local function observeTransient()
    local f = api("observeNow")
    if f then pcall(f) end
end

if Events.OnZombieDead then Events.OnZombieDead.Add(observeTransient) end
if Events.OnExitVehicle then Events.OnExitVehicle.Add(observeTransient) end
if Events.OnUseVehicle then Events.OnUseVehicle.Add(observeTransient) end
if Events.OnPlayerAttackFinished then
    Events.OnPlayerAttackFinished.Add(observeTransient)
end

-- Completion hooks cover actions for which Build 42 exposes no stable global
-- event. Wrapping `complete` (not start/perform) means cancelled or invalid
-- actions never enter story memory. Each Java call is allow-listed again.
local function actionEvent(kind, label)
    local f = api("recordAction")
    if f then pcall(f, kind, tostring(label or "something")) end
end

local function hookComplete(class, kind, label)
    if type(class) ~= "table" or type(class.complete) ~= "function"
            or class._pzstoryCompleteHook then return end
    class._pzstoryCompleteHook = true
    local original = class.complete
    class.complete = function(self, ...)
        local result = original(self, ...)
        if result == true then
            local text = "something"
            if label then pcall(function() text = label(self) or text end) end
            actionEvent(kind, text)
        end
        return result
    end
end

hookComplete(ISCraftAction, "crafted", function(a)
    return a.recipe and a.recipe:getName() or "something useful"
end)
hookComplete(ISRepairClothing, "repaired", function(a)
    return a.clothing and a.clothing:getName() or "clothing"
end)
hookComplete(ISRepairEngine, "repaired", function() return "a vehicle engine" end)
hookComplete(ISRepairLightbar, "repaired", function() return "a vehicle lightbar" end)
hookComplete(ISSeedActionNew, "farmed", function(a)
    return "planted " .. tostring(a.typeOfSeed or "seeds")
end)
hookComplete(ISWaterPlantAction, "farmed", function() return "watered a crop" end)
hookComplete(ISLightFromPetrol, "fire_started", function() return "a fire" end)
hookComplete(ISLightFromLiterature, "fire_started", function() return "a fire" end)
hookComplete(ISLightFromKindle, "fire_started", function() return "a fire" end)
hookComplete(ISEatFoodAction, "item_used", function(a)
    return a.item and a.item:getName() or "food"
end)
hookComplete(ISDrinkFromBottle, "item_used", function(a)
    return a.item and a.item:getName() or "a drink"
end)
hookComplete(ISDrinkFluidAction, "item_used", function() return "a drink" end)

if type(ISOpenCloseDoor) == "table" and type(ISOpenCloseDoor.complete) == "function"
        and not ISOpenCloseDoor._pzstoryCompleteHook then
    ISOpenCloseDoor._pzstoryCompleteHook = true
    local originalDoorComplete = ISOpenCloseDoor.complete
    ISOpenCloseDoor.complete = function(self, ...)
        local wasOpen = self.item and self.item:IsOpen() or false
        local result = originalDoorComplete(self, ...)
        if result == true then
            actionEvent(wasOpen and "door_closed" or "door_opened", "door")
        end
        return result
    end
end

-- The campaign store lives inside the save folder, so it must be re-pointed
-- every time a save loads. Without this, loading a second save would keep
-- showing the first one's book.
Events.OnGameStart.Add(function()
    instance = nil
    observer = nil
    nextObserverAt = 0
    local f = api("onGameStart")
    if f then
        pcall(f)
        observer = api("observeWorld")
        if observer then
            pcall(observer)
            nextObserverAt = getTimestampMs() + 5000
        end
    else
        print("[PZStory] JAR bridge is " .. tostring(javaApi()) .. " but the interface needs "
              .. NEEDS_API .. " - restart Project Zomboid (loading a save reloads"
              .. " Lua but not Java).")
    end
end)

-- MUST be OnTickEvenPaused: OnTick does not fire at speed zero.
Events.OnTickEvenPaused.Add(function()
    -- 2.0 event memory is entirely local. Throttle before crossing the
    -- Lua/Java bridge, and do no world scan while the simulation is paused.
    -- Java enforces the same cadence as a second line of defence.
    if observer ~= nil and getGameSpeed() > 0 then
        local now = getTimestampMs()
        if now >= nextObserverAt then
            nextObserverAt = now + 5000
            pcall(observer)
        end
    end
    if instance ~= nil and instance.isOpen then instance:drain() end
end)
