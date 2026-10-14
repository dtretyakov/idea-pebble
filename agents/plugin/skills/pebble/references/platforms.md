# Writing for every platform

Every platform's `pebble.h` declares the whole API, so code compiles everywhere. What differs is
the screen, and which calls actually do something. `pebble_api` says where a call is a stub.

## Screen shape and colour

These macros are defined on every platform and pick their argument at compile time:

```c
GRect bounds = layer_get_unobstructed_bounds(window_get_root_layer(window));
int inset = PBL_IF_ROUND_ELSE(18, 4);               // round: chalk, gabbro
GColor background = PBL_IF_COLOR_ELSE(GColorOxfordBlue, GColorBlack);   // b/w: aplite, diorite, flint
GTextAlignment align = PBL_IF_RECT_ELSE(GTextAlignmentLeft, GTextAlignmentCenter);
```

Also `PBL_IF_BW_ELSE`, `PBL_IF_MICROPHONE_ELSE` and `PBL_IF_HEALTH_ELSE`. For a block of code,
use `#if defined(PBL_ROUND)`, `PBL_RECT`, `PBL_COLOR`, `PBL_BW`, `PBL_HEALTH` or `PBL_MICROPHONE`.
To pick a value per platform, use `PBL_PLATFORM_SWITCH(PBL_PLATFORM_TYPE_CURRENT, aplite, basalt,
chalk, diorite, emery, flint, gabbro)`.

On a round screen the corners are cut off: keep text inside the inscribed square, or use
`graphics_text_layout_get_content_size` and `text_layer_enable_screen_text_flow_and_paging`.

The unobstructed area shrinks when a timeline Quick View peeks in. Subscribe with
`unobstructed_area_service_subscribe` if the layout must follow it. Test it with the
`pebble_emulator` step `quick-view on`.

## Hardware some platforms lack

The speaker, the touch screen, the RGB backlight and some health metrics exist only on newer
watches. Elsewhere their functions are macros that do nothing, such as
`#define speaker_play_notes(...) (0)`. The build does not complain. The plugin's inspection, and
`pebble_api`, say where.

Touch (`touch_service_subscribe`) is real only on emery and gabbro. The `pebble_emulator` step
`tap x y` works only there.

## Memory

An app's RAM is 24 KB on aplite, 64 KB on basalt, chalk, diorite and flint, and 128 KB on emery
and gabbro. Code, globals and the heap share it. `pebble_build` reports each platform's footprint
and free heap, and marks a tight one.

On aplite especially:

- keep fonts to the system ones (`fonts_get_system_font`);
- load bitmaps when a window appears, and destroy them when it goes;
- avoid large static buffers.

## Resources

Images, fonts and raw data go in `resources/` and are listed in `package.json` under
`pebble.resources.media`. Each entry has a `type`, a `name` (`RESOURCE_ID_<name>` in C) and a
`file`. A platform-specific variant sits beside the default as `name~color.png`, `name~bw.png`,
`name~round.png` or `name~<platform>.png`.

## Alloy

Alloy (`"projectType": "moddable"`) runs JavaScript on emery and gabbro only. Its modules — Poco
for drawing (`import Poco from "commodetto/Poco"`), Piu for UI — are in the SDK's typings, which
`pebble_api` searches. Sources live in `src/embeddedjs/`.
