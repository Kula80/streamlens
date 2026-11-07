--[[
StreamLens for OBS

  * One click creates the phone camera source (SRT listener) and the branded right-back screen.
  * If the phone's video stops, viewers see the right-back screen after a few seconds. When the
    phone comes back and is steady, the camera returns by itself. Nobody has to click anything.

Load it in OBS: Tools → Scripts → + → streamlens.lua
]]

obs = obslua

local CAMERA_NAME = "StreamLens camera"
local SLATE_NAME = "StreamLens right-back screen"
local DEFAULT_MESSAGE = "Sorry for the inconvenience. We'll be right back."

local cfg = {}
local lost_since = nil
local ok_since = nil
-- nil = unknown (e.g. OBS was closed while the screen was showing), so the first decision always applies.
local slate_shown = nil
-- Auto mode only starts once the phone has been live, so the screen doesn't appear before service.
local armed = false
-- nil = automatic, true = always shown, false = always hidden
local manual = nil
local script_settings = nil

local LATENCIES = { { "Low latency (120 ms)", 120 }, { "Balanced (300 ms)", 300 }, { "Rock solid (1000 ms)", 1000 } }

---------------------------------------------------------------------------------------------------
-- Helpers
---------------------------------------------------------------------------------------------------

local function urlencode(s)
	return (s:gsub("[^%w%-_%.~]", function(c)
		return string.format("%%%02X", string.byte(c))
	end))
end

-- OBS colours are 0xAABBGGRR; the page wants #RRGGBB.
local function color_to_hex(c)
	local r = c % 256
	local g = math.floor(c / 256) % 256
	local b = math.floor(c / 65536) % 256
	return string.format("#%02X%02X%02X", r, g, b)
end

local function slate_url()
	-- Folder names can contain #, &, accents and so on; keep only / and the drive's : as they are.
	local dir = script_path():gsub("\\", "/"):gsub("[^%w%-_%.~/:]", function(c)
		return string.format("%%%02X", string.byte(c))
	end)
	local params = {
		"msg=" .. urlencode(cfg.message ~= "" and cfg.message or DEFAULT_MESSAGE),
		"sub=" .. urlencode(cfg.sub),
		"name=" .. urlencode(cfg.name),
		"color=" .. urlencode(color_to_hex(cfg.color)),
		"logo=" .. urlencode(cfg.logo),
		"photo=" .. urlencode(cfg.photo),
		"audio=" .. (cfg.audio and "1" or "0"),
	}
	return "file:///" .. dir .. "slate/slate.html?" .. table.concat(params, "&")
end

local function camera_input()
	return string.format("srt://0.0.0.0:%d?mode=listener&latency=%d&timeout=2000000", cfg.port, cfg.latency * 1000)
end

local function for_each_scene(fn)
	local scenes = obs.obs_frontend_get_scenes()
	if scenes == nil then
		return
	end
	for _, scene_source in ipairs(scenes) do
		fn(obs.obs_scene_from_source(scene_source))
	end
	obs.source_list_release(scenes)
end

local function set_slate_visible(visible)
	local found = false
	for_each_scene(function(scene)
		local item = obs.obs_scene_find_source(scene, SLATE_NAME)
		if item ~= nil then
			obs.obs_sceneitem_set_visible(item, visible)
			found = true
		end
	end)
	-- Scenes aren't loaded yet while OBS starts; try again on the next tick.
	if found then
		slate_shown = visible
	end
end

---------------------------------------------------------------------------------------------------
-- Creating the sources
---------------------------------------------------------------------------------------------------

local function changed_input(source, settings)
	local current = obs.obs_source_get_settings(source)
	local changed = obs.obs_data_get_string(current, "input") ~= obs.obs_data_get_string(settings, "input")
	obs.obs_data_release(current)
	return changed
end

local function set_status(text)
	if script_settings ~= nil then
		obs.obs_data_set_string(script_settings, "status", text)
	end
end

local function upsert_source(id, name, fill)
	local settings = obs.obs_data_create()
	fill(settings)
	local source = obs.obs_get_source_by_name(name)
	if source == nil then
		source = obs.obs_source_create(id, name, settings, nil)
	elseif id ~= "ffmpeg_source" or changed_input(source, settings) then
		-- Updating the camera restarts its listener, so only do it when the address changed.
		obs.obs_source_update(source, settings)
	end
	obs.obs_data_release(settings)
	return source
end

local function update_slate_source()
	local source = upsert_source("browser_source", SLATE_NAME, function(s)
		obs.obs_data_set_bool(s, "is_local_file", false)
		obs.obs_data_set_string(s, "url", slate_url())
		obs.obs_data_set_int(s, "width", 1920)
		obs.obs_data_set_int(s, "height", 1080)
		-- Reload when shown, so the fade-in plays every time.
		obs.obs_data_set_bool(s, "restart_when_active", true)
		obs.obs_data_set_bool(s, "reroute_audio", false)
	end)
	return source
end

local function update_camera_source()
	return upsert_source("ffmpeg_source", CAMERA_NAME, function(s)
		obs.obs_data_set_bool(s, "is_local_file", false)
		obs.obs_data_set_string(s, "input", camera_input())
		obs.obs_data_set_string(s, "input_format", "mpegts")
		obs.obs_data_set_int(s, "reconnect_delay_sec", 1)
		obs.obs_data_set_int(s, "buffering_mb", 1)
		obs.obs_data_set_bool(s, "hw_decode", true)
		-- Hold the last frame instead of going black during a short glitch.
		obs.obs_data_set_bool(s, "clear_on_media_end", false)
		obs.obs_data_set_bool(s, "close_when_inactive", false)
		obs.obs_data_set_bool(s, "restart_on_activate", false)
	end)
end

local function setup_sources()
	local camera = update_camera_source()
	local slate = update_slate_source()

	-- Add both to the current scene if missing: camera first, screen on top, hidden.
	local scene_source = obs.obs_frontend_get_current_scene()
	local scene = obs.obs_scene_from_source(scene_source)
	local scene_name = obs.obs_source_get_name(scene_source)
	if obs.obs_scene_find_source(scene, CAMERA_NAME) == nil then
		local item = obs.obs_scene_add(scene, camera)
		local bounds = obs.vec2()
		bounds.x = obs.obs_source_get_base_width(scene_source)
		bounds.y = obs.obs_source_get_base_height(scene_source)
		obs.obs_sceneitem_set_bounds_type(item, obs.OBS_BOUNDS_SCALE_INNER)
		obs.obs_sceneitem_set_bounds(item, bounds)
		obs.obs_sceneitem_set_order(item, obs.OBS_ORDER_MOVE_BOTTOM)
	end
	if obs.obs_scene_find_source(scene, SLATE_NAME) == nil then
		local item = obs.obs_scene_add(scene, slate)
		local bounds = obs.vec2()
		bounds.x = obs.obs_source_get_base_width(scene_source)
		bounds.y = obs.obs_source_get_base_height(scene_source)
		obs.obs_sceneitem_set_bounds_type(item, obs.OBS_BOUNDS_STRETCH)
		obs.obs_sceneitem_set_bounds(item, bounds)
		obs.obs_sceneitem_set_visible(item, false)
	end
	obs.obs_source_release(scene_source)
	obs.obs_source_release(camera)
	obs.obs_source_release(slate)
	set_slate_visible(false)
	set_status(string.format(
		"✅ Ready in scene \"%s\". The camera stays blank until the phone connects. "
			.. "On the phone, tap Go live: it finds this computer by itself (port %d).",
		scene_name, cfg.port))
end

---------------------------------------------------------------------------------------------------
-- Watching the camera
---------------------------------------------------------------------------------------------------

local function camera_playing()
	local source = obs.obs_get_source_by_name(CAMERA_NAME)
	if source == nil then
		return nil
	end
	local playing = obs.obs_source_media_get_state(source) == obs.OBS_MEDIA_STATE_PLAYING
	obs.obs_source_release(source)
	return playing
end

local function tick()
	if manual ~= nil then
		if slate_shown ~= manual then
			set_slate_visible(manual)
		end
		return
	end

	local playing = camera_playing()
	if playing == nil then
		return
	end
	local now = obs.os_gettime_ns() / 1e9

	if playing then
		armed = true
		lost_since = nil
		ok_since = ok_since or now
		-- Only switch back once the phone has been steady for a moment.
		if slate_shown ~= false and now - ok_since >= cfg.recover then
			set_slate_visible(false)
		end
	else
		ok_since = nil
		lost_since = lost_since or now
		if armed and slate_shown ~= true and now - lost_since >= cfg.delay then
			set_slate_visible(true)
		elseif not armed and slate_shown == nil then
			-- Before the phone has ever connected, viewers shouldn't see the screen.
			set_slate_visible(false)
		end
	end
end

local MODE_TEXT = {
	auto = "Automatic: shows by itself if the phone's video drops",
	shown = "Showing now (manual). Click Back to automatic when ready.",
	hidden = "Kept hidden (manual): it won't appear even if the phone drops. Click Back to automatic.",
}

local function show_mode()
	if script_settings ~= nil then
		local key = manual == true and "shown" or manual == false and "hidden" or "auto"
		obs.obs_data_set_string(script_settings, "mode", MODE_TEXT[key])
	end
end

local function set_manual(value)
	manual = value
	if value == nil then
		-- Start counting afresh, so the camera doesn't snap back on a timer from before.
		ok_since = nil
		lost_since = nil
	else
		set_slate_visible(value)
	end
	show_mode()
end

---------------------------------------------------------------------------------------------------
-- OBS script interface
---------------------------------------------------------------------------------------------------

function script_description()
	return [[<h3>StreamLens</h3>
<p>Receives your phone camera and shows a branded <b>right-back screen</b> to viewers
automatically if the phone's video drops.</p>
<p>1. Fill in your branding below.<br>
2. Click <b>Set up phone camera</b>.<br>
3. On the phone, tap <b>Go live</b>. It finds this computer by itself.</p>
<p>If Windows asks whether OBS may use the network, allow it on <b>Private networks</b>.</p>]]
end

function script_properties()
	local props = obs.obs_properties_create()

	obs.obs_properties_add_int(props, "port", "Port", 1024, 65535, 1)
	local lat = obs.obs_properties_add_list(props, "latency", "Stability", obs.OBS_COMBO_TYPE_LIST, obs.OBS_COMBO_FORMAT_INT)
	for _, l in ipairs(LATENCIES) do
		obs.obs_property_list_add_int(lat, l[1], l[2])
	end
	obs.obs_properties_add_button(props, "setup", "Set up phone camera", function()
		setup_sources()
		return true
	end)
	obs.obs_properties_add_text(props, "status", "Status", obs.OBS_TEXT_INFO)

	obs.obs_properties_add_text(props, "message", "Message to viewers", obs.OBS_TEXT_MULTILINE)
	obs.obs_properties_add_text(props, "sub", "Second line (optional)", obs.OBS_TEXT_DEFAULT)
	obs.obs_properties_add_text(props, "name", "Church or event name", obs.OBS_TEXT_DEFAULT)
	obs.obs_properties_add_color(props, "color", "Brand colour")
	obs.obs_properties_add_path(props, "logo", "Logo", obs.OBS_PATH_FILE, "Images (*.png *.jpg *.jpeg *.svg *.webp)", nil)
	obs.obs_properties_add_path(props, "photo", "Background photo (optional)", obs.OBS_PATH_FILE, "Images (*.png *.jpg *.jpeg *.webp)", nil)
	obs.obs_properties_add_bool(props, "audio", "Show \"Audio continues\" (when sound comes from the mixer)")

	obs.obs_properties_add_int_slider(props, "delay", "Show screen after (seconds without video)", 1, 10, 1)
	obs.obs_properties_add_int_slider(props, "recover", "Return to camera after (seconds of steady video)", 1, 10, 1)

	obs.obs_properties_add_text(props, "mode", "Right-back screen", obs.OBS_TEXT_INFO)
	obs.obs_properties_add_button(props, "show", "Show right-back screen now", function()
		set_manual(true)
		return true
	end)
	obs.obs_properties_add_button(props, "hide", "Hide it (stay hidden)", function()
		set_manual(false)
		return true
	end)
	obs.obs_properties_add_button(props, "auto", "Back to automatic", function()
		set_manual(nil)
		return true
	end)
	return props
end

function script_defaults(settings)
	obs.obs_data_set_default_int(settings, "port", 9710)
	obs.obs_data_set_default_int(settings, "latency", 300)
	obs.obs_data_set_default_string(settings, "message", DEFAULT_MESSAGE)
	obs.obs_data_set_default_int(settings, "color", 0xFF89343C) -- #3C3489
	obs.obs_data_set_default_int(settings, "delay", 2)
	obs.obs_data_set_default_int(settings, "recover", 2)
end

function script_update(settings)
	cfg.port = obs.obs_data_get_int(settings, "port")
	cfg.latency = obs.obs_data_get_int(settings, "latency")
	cfg.message = obs.obs_data_get_string(settings, "message")
	cfg.sub = obs.obs_data_get_string(settings, "sub")
	cfg.name = obs.obs_data_get_string(settings, "name")
	cfg.color = obs.obs_data_get_int(settings, "color")
	cfg.logo = obs.obs_data_get_string(settings, "logo")
	cfg.photo = obs.obs_data_get_string(settings, "photo")
	cfg.audio = obs.obs_data_get_bool(settings, "audio")
	cfg.delay = obs.obs_data_get_int(settings, "delay")
	cfg.recover = obs.obs_data_get_int(settings, "recover")

	-- Keep existing sources in sync as settings are edited, so nobody has to click Set up again.
	local existing = obs.obs_get_source_by_name(SLATE_NAME)
	if existing ~= nil then
		obs.obs_source_release(existing)
		obs.obs_source_release(update_slate_source())
	end
	local camera = obs.obs_get_source_by_name(CAMERA_NAME)
	if camera ~= nil then
		obs.obs_source_release(camera)
		-- Only restarts the listener if the port or stability really changed.
		obs.obs_source_release(update_camera_source())
	end
end

function script_load(settings)
	script_settings = settings
	local camera = obs.obs_get_source_by_name(CAMERA_NAME)
	if camera ~= nil then
		obs.obs_source_release(camera)
		set_status("✅ Phone camera is set up. Waiting for the phone to go live.")
	else
		set_status("Not set up yet. Click Set up phone camera.")
	end
	show_mode()
	obs.timer_add(tick, 250)
end

function script_unload()
	obs.timer_remove(tick)
end
