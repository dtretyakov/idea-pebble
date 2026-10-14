  GTextOverflowModeFill
} GTextOverflowMode;

//! Text aligment controls the way the text is aligned inside the box the text is drawn into.
//! @see graphics_draw_text
//! @see text_layer_set_text_alignment
typedef enum {
  //! Aligns the text to the left of the drawing box
  GTextAlignmentLeft,
  //! Aligns the text centered inside the drawing box
  GTextAlignmentCenter,
  //! Aligns the text to the right of the drawing box
  GTextAlignmentRight,
} GTextAlignment;

struct GTextAttributes;

//! @{

struct TextLayer;
typedef struct TextLayer TextLayer;


//! Sets the pointer to the string where the TextLayer is supposed to find the text
//! at a later point in time, when it needs to draw itself.
//! @param text_layer The TextLayer of which to set the text
//! @param text The new text to set onto the TextLayer. This must be a null-terminated and valid UTF-8 string!
//! @note The string is not copied, so its buffer most likely cannot be stack allocated,
//! but is recommended to be a buffer that is long-lived, at least as long as the TextLayer
//! is part of a visible Layer hierarchy.
//! @see text_layer_get_text
void text_layer_set_text(TextLayer *text_layer, const char *text);

//! Convenience macro to switch between two expression depending on the screen of the platform.
//! On platforms with round screen, the first expression will be chosen, the second otherwise.
#define PBL_IF_ROUND_ELSE(if_true, if_false) (if_true)

//! Convenience macro to switch between two expression depending on the screen of the platform.
//! On black& white platforms, the first expression will be chosen, the second otherwise.

//! Maximum number of notes in a single speaker_play_notes() call.
#define SPEAKER_MAX_NOTES 256

//! Maximum number of parallel tracks in a single speaker_play_tracks() call.
#define SPEAKER_MAX_TRACKS 4

//! Maximum total sample-data bytes across all tracks in one speaker_play_tracks()
//! call.
#define SPEAKER_MAX_SAMPLE_BYTES_TOTAL (16 * 1024)

#define speaker_play_notes(...) (0)

