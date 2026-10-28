// Toggles.cpp - merged small enhancement toggles (BattleShipPort, KhalilXmodz).
// Each section below used to be its own file. Behaviour is unchanged.
#include "enhancements.h"
#include "internal/buttons.h"
#include <libultraship/bridge/consolevariablebridge.h>

// ===================== AutoZCancel =====================
namespace ssb64 {
    namespace enhancements {

        const char* AutoZCancelCVarName() {
            return "gEnhancements.CasualRules.AutoZCancel";
        }

    } // namespace enhancements
} // namespace ssb64

extern "C" int port_enhancement_IsAutoZCancelEnabled(void) {
    return CVarGetInteger(ssb64::enhancements::AutoZCancelCVarName(), 0) != 0;
}

// ===================== BonusStages =====================
// Issue #267 — the three port-added stages (Final Destination, Metal Cavern,
// Battlefield) live on their own stage-select page. This toggle hides that
// page for a vanilla stage roster. Default ON: the bonus stages are a
// headline port feature; hiding them is the opt-in.
//
// Consumed by mnMapsCheckLocked (decomp/src/mn/mnmaps/mnmaps.c): when the
// toggle is off the three gkinds report as locked, which in one stroke skips
// their icons, refuses page-jump landings, excludes them from random stage
// selection, and reroutes a restored cursor back to page 0.
constexpr const char* kBonusStagesCVar = "gEnhancements.BonusStagesEnabled";

extern "C" int port_enhancement_bonus_stages_enabled(void) {
    return CVarGetInteger(kBonusStagesCVar, 1) != 0;
}

namespace ssb64 {
namespace enhancements {

const char* BonusStagesCVarName() {
    return kBonusStagesCVar;
}

} // namespace enhancements
} // namespace ssb64

// ===================== BootToVSCSS =====================
namespace ssb64 {
    namespace enhancements {

        const char* BootToVSCSSCVarName() {
            return "gEnhancements.BootToVSCSS";
        }

    } // namespace enhancements
} // namespace ssb64

extern "C" int port_enhancement_boot_to_vs_css(void) {
    return CVarGetInteger(ssb64::enhancements::BootToVSCSSCVarName(), 0) != 0;
}

// ===================== CStickSmash =====================
namespace {

constexpr const char* kCStickSmashCVars[PORT_ENHANCEMENT_MAX_PLAYERS] = {
    "gEnhancements.CStickSmash.P1",
    "gEnhancements.CStickSmash.P2",
    "gEnhancements.CStickSmash.P3",
    "gEnhancements.CStickSmash.P4",
};

} // namespace

extern "C" void port_enhancement_c_stick_smash(int player_index, unsigned short* button_hold, unsigned short* button_tap, signed char* stick_x, signed char* stick_y, unsigned short tap_pre_remap) {
    using namespace ssb64::enhancements::internal;

    if (player_index < 0 || player_index >= PORT_ENHANCEMENT_MAX_PLAYERS) return;
    if (!CVarGetInteger(kCStickSmashCVars[player_index], 0)) return;

    // Always strip C-bits from both hold/tap so the engine never sees the raw
    // C-button input on this player when the remap is on (otherwise the stock
    // jump assignments would also fire alongside the synthesised attack).
    const unsigned short cbits = U_CBUTTON | D_CBUTTON | L_CBUTTON | R_CBUTTON;
    *button_hold &= ~cbits;
    *button_tap  &= ~cbits;

    // Fire the smash/aerial only on the rising edge of the C-button. A real
    // C-stick is analog and self-centers; translating it from a digital button
    // means as long as the C-button is pressed, the engine would otherwise see
    // stick pinned at ±80 — which manifests as drift in the air / running on
    // the ground (issue #97 [1]). Holding A across multiple frames also isn't
    // useful in SSB64 (no smash-attack charging), so a single-frame edge is
    // enough to register the attack.
    //
    // Cross-axis is forced to 0 so the input lands as a pure-axis tilt rather
    // than a 45° diagonal when the L-stick is already held sideways — without
    // this, e.g. C-up while running right reads as (80, 80) and the engine's
    // dominant-axis logic picks fair/bair instead of upair (issue #97 [2]).
    if (tap_pre_remap & U_CBUTTON) {
        *stick_x = 0;
        *stick_y = 80;
        *button_hold |= A_BUTTON;
        *button_tap  |= A_BUTTON;
    } else if (tap_pre_remap & D_CBUTTON) {
        *stick_x = 0;
        *stick_y = -80;
        *button_hold |= A_BUTTON;
        *button_tap  |= A_BUTTON;
    } else if (tap_pre_remap & L_CBUTTON) {
        *stick_x = -80;
        *stick_y = 0;
        *button_hold |= A_BUTTON;
        *button_tap  |= A_BUTTON;
    } else if (tap_pre_remap & R_CBUTTON) {
        *stick_x = 80;
        *stick_y = 0;
        *button_hold |= A_BUTTON;
        *button_tap  |= A_BUTTON;
    }
}

namespace ssb64 {
namespace enhancements {

const char* CStickSmashCVarName(int playerIndex) {
    if (playerIndex < 0 || playerIndex >= PORT_ENHANCEMENT_MAX_PLAYERS) {
        return kCStickSmashCVars[0];
    }
    return kCStickSmashCVars[playerIndex];
}

} // namespace enhancements
} // namespace ssb64

// ===================== ClassicCoop =====================
namespace ssb64 {
    namespace enhancements {

        const char* ClassicCoopCVarName() {
            return "gEnhancements.ClassicCoop";
        }

        const char* ClassicCoopFriendlyFireCVarName() {
            return "gEnhancements.ClassicCoopFriendlyFire";
        }

    } // namespace enhancements
} // namespace ssb64

// Latched at boot (port_classic_coop_latch) so mid-session toggles only take
// effect after a restart, matching the "(Needs reload)" menu label. -1 = not
// yet latched; falls back to a live read if queried before the latch runs.
static int sClassicCoopLatched = -1;

// Set while the VS CSS overlay is serving Classic mode (between the 1P-mode
// redirect and the CSS handing off to nSCKind1PGame / backing out). Keyed
// explicitly at the redirect site so BootToVSCSS — which also enters
// nSCKindPlayersVS — never trips it.
static int sClassicCoopContext = 0;

extern "C" void port_classic_coop_latch(void) {
    sClassicCoopLatched = CVarGetInteger(ssb64::enhancements::ClassicCoopCVarName(), 1) != 0;
}

extern "C" int port_enhancement_classic_coop(void) {
    if (sClassicCoopLatched < 0) {
        port_classic_coop_latch();
    }
    return sClassicCoopLatched;
}

extern "C" int port_classic_coop_context(void) {
    return sClassicCoopContext;
}

// Live read (no latch): consumed once per stage setup, so a mid-run toggle
// simply applies from the next stage.
extern "C" int port_classic_coop_friendly_fire(void) {
    return CVarGetInteger(ssb64::enhancements::ClassicCoopFriendlyFireCVarName(), 0) != 0;
}

extern "C" void port_classic_coop_set_context(int active) {
    sClassicCoopContext = active != 0;
}

// ===================== CompRuleset =====================
namespace ssb64 {
    namespace enhancements {

        const char* CompRulesetCVarName() {
            return "gEnhancements.CompRuleset";
        }

    } // namespace enhancements
} // namespace ssb64

extern "C" int port_get_comp_ruleset(void) {
    // Reads the CVar from the UI bridge.
    // The != 0 ensures it strictly returns a 1 or 0 boolean state for the C engine.
    return CVarGetInteger(ssb64::enhancements::CompRulesetCVarName(), 0) != 0;
}

// ===================== CpuLevel9 =====================
namespace ssb64 {
    namespace enhancements {

        const char* CpuLevel9CVarName() {
            return "gEnhancements.CpuLevel9";
        }

    } // namespace enhancements
} // namespace ssb64

extern "C" int port_enhancement_cpu_level_9(void) {
    return CVarGetInteger(ssb64::enhancements::CpuLevel9CVarName(), 0) != 0;
}

// ===================== DPadJump =====================
namespace {

constexpr const char* kDPadJumpCVars[PORT_ENHANCEMENT_MAX_PLAYERS] = {
    "gEnhancements.DPadJump.P1",
    "gEnhancements.DPadJump.P2",
    "gEnhancements.DPadJump.P3",
    "gEnhancements.DPadJump.P4",
};

} // namespace

extern "C" void port_enhancement_dpad_jump(int player_index, unsigned short* button_hold, unsigned short* button_tap, unsigned short tap_pre_remap) {
    using namespace ssb64::enhancements::internal;

    if (player_index < 0 || player_index >= PORT_ENHANCEMENT_MAX_PLAYERS) return;
    if (!CVarGetInteger(kDPadJumpCVars[player_index], 0)) return;

    // If any D-Pad direction is pressed, inject a C-Up (Jump) into the engine.
    if (*button_hold & (U_JPAD | D_JPAD | L_JPAD | R_JPAD)) {
        *button_hold |= U_CBUTTON;
        if (tap_pre_remap & (U_JPAD | D_JPAD | L_JPAD | R_JPAD)) {
            *button_tap |= U_CBUTTON;
        }
    }
}

namespace ssb64 {
namespace enhancements {

const char* DPadJumpCVarName(int playerIndex) {
    if (playerIndex < 0 || playerIndex >= PORT_ENHANCEMENT_MAX_PLAYERS) {
        return kDPadJumpCVars[0];
    }
    return kDPadJumpCVars[playerIndex];
}

} // namespace enhancements
} // namespace ssb64

// ===================== DisableHUD =====================
constexpr const char* kDisableHUDCVar = "gEnhancements.DisableHUD";

extern "C" {
    bool port_enhancement_is_hud_disabled(void) {
        return CVarGetInteger(kDisableHUDCVar, 0) != 0;
    }
}

namespace ssb64 {
    namespace enhancements {

        const char* DisableHUDCVarName() {
            return kDisableHUDCVar;
        }

    } // namespace enhancements
} // namespace ssb64

// ===================== FailedZCancelFlash =====================
namespace ssb64 {
    namespace enhancements {

        const char* FailedZCancelCVarName() {
            return "gEnhancements.CasualRules.FailedZCancelFlash";
        }

    } // namespace enhancements
} // namespace ssb64

extern "C" int port_enhancement_IsFailedZCancelFlashEnabled(void) {
    return CVarGetInteger(ssb64::enhancements::FailedZCancelCVarName(), 0) != 0;
}

// ===================== HitboxView =====================
namespace {

constexpr const char* kHitboxViewCVar = "gEnhancements.HitboxView";

// Mirrors dbObjectDisplayMode in src/sys/develop.h. Duplicated here to keep the
// C ABI of port_enhancement_hitbox_display_override() free of game headers.
enum {
    kDisplayModeMaster = 0,
    kDisplayModeHitCollisionFill = 1,
    kDisplayModeHitAttackOutline = 2,
    kDisplayModeMapCollision = 3,
};

} // namespace

extern "C" int port_enhancement_hitbox_display_override(int current_mode) {
    int setting = CVarGetInteger(kHitboxViewCVar, 0);
    switch (setting) {
        case 1:
            return kDisplayModeHitCollisionFill;
        case 2:
            return kDisplayModeHitAttackOutline;
        default:
            return current_mode;
    }
}

namespace ssb64 {
namespace enhancements {

const char* HitboxViewCVarName() {
    return kHitboxViewCVar;
}

} // namespace enhancements
} // namespace ssb64

// ===================== NeutralSpawns =====================
namespace ssb64 {
    namespace enhancements {

        const char* NeutralSpawnsCVarName() {
            return "gEnhancements.NeutralSpawns";
        }

    } // namespace enhancements
} // namespace ssb64

extern "C" int port_enhancement_neutral_spawns(void) {
    return CVarGetInteger(ssb64::enhancements::NeutralSpawnsCVarName(), 0) != 0;
}

// ===================== SkipResultsScreen =====================
namespace ssb64 {
    namespace enhancements {

        const char* SkipResultsScreenCVarName() {
            return "gEnhancements.SkipResultsScreen";
        }

    } // namespace enhancements
} // namespace ssb64

extern "C" int port_enhancement_skip_results_screen(void) {
    return CVarGetInteger(ssb64::enhancements::SkipResultsScreenCVarName(), 0) != 0;
}

// ===================== TapJump =====================
namespace {

constexpr const char* kTapJumpCVars[PORT_ENHANCEMENT_MAX_PLAYERS] = {
    "gEnhancements.TapJumpDisabled.P1",
    "gEnhancements.TapJumpDisabled.P2",
    "gEnhancements.TapJumpDisabled.P3",
    "gEnhancements.TapJumpDisabled.P4",
};

} // namespace

extern "C" int port_enhancement_tap_jump_disabled(int playerIndex) {
    if (playerIndex < 0 || playerIndex >= PORT_ENHANCEMENT_MAX_PLAYERS) {
        return 0;
    }
    return CVarGetInteger(kTapJumpCVars[playerIndex], 0) != 0;
}

namespace ssb64 {
namespace enhancements {

const char* TapJumpCVarName(int playerIndex) {
    if (playerIndex < 0 || playerIndex >= PORT_ENHANCEMENT_MAX_PLAYERS) {
        return kTapJumpCVars[0];
    }
    return kTapJumpCVars[playerIndex];
}

} // namespace enhancements
} // namespace ssb64

// ===================== UnlockCheats =====================
extern "C" {
    int port_cheat_unlock_all()        { return CVarGetInteger("gCheats.UnlockAll", 0); }
    int port_cheat_unlock_luigi()      { return CVarGetInteger("gCheats.UnlockLuigi", 0); }
    int port_cheat_unlock_ness()       { return CVarGetInteger("gCheats.UnlockNess", 0); }
    int port_cheat_unlock_captain()    { return CVarGetInteger("gCheats.UnlockCaptain", 0); }
    int port_cheat_unlock_purin()      { return CVarGetInteger("gCheats.UnlockPurin", 0); }
    int port_cheat_unlock_inishie()    { return CVarGetInteger("gCheats.UnlockInishie", 0); }
    int port_cheat_unlock_soundtest()  { return CVarGetInteger("gCheats.UnlockSoundTest", 0); }
    int port_cheat_unlock_itemswitch() { return CVarGetInteger("gCheats.UnlockItemSwitch", 0); }
}

// ===================== volume =====================
// Wrap everything in extern "C" so the C-based engine can link to these functions
extern "C" {

    // Volume level getters pulled from the UI cvars
    float port_get_master_volume() {
        return CVarGetFloat("gSettings.Audio.MasterVolume", 1.0f);
    }

    float port_get_music_volume() {
        return CVarGetFloat("gSettings.Audio.MusicVolume", 1.0f);
    }

    float port_get_sfx_volume() {
        return CVarGetFloat("gSettings.Audio.SFXVolume", 1.0f);
    }

    float port_get_voice_volume() {
        return CVarGetFloat("gSettings.Audio.VoiceVolume", 1.0f);
    }

}
