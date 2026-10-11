package com.openkhub.sensefield;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Audited finite rule registry. Identity recognition never executes an arbitrary rule from JSON. */
final class Match3Rules {
    static final String CATALOG_ID="kxxxl-reference-2026-10-11-v1";
    static final int VERSION=1;
    static final Set<String> IDS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "ordinary_uncovered_animal","line_horizontal","line_vertical","blast","magic_bird",
            "combo_bird_color","combo_bird_bird","combo_bird_line","combo_bird_blast","combo_line_line",
            "combo_line_blast","combo_blast_blast","combo_dye_dye","combo_dye_star","combo_star_star",
            "combo_energy_energy","combo_dye_egg_bird","snow","frozen_ice","locked_animal","poison",
            "honey_cover","chest","stone_gate","transport","coin","leaf","furball","invincible_furball",
            "snowflake","cloud","flower_cluster","cage","egg_shell","ice_barrier","chick_hatch_goal","egg",
            "penguin","snow_bear","sprite","one_eye","dolphin","gold_pod","key","gem","star","jellyfish",
            "cannon","shell","puffer","magic_stone","dye_egg","dye_baby","star_bottle","energy_bottle",
            "octopus","cuttlefish","moving_floor","tool_hammer","tool_shuffle","tool_extra_steps",
            "tool_start_special","tool_undo","stationary_single_layer_ice","single_clear_honey",
            "multistage_iceflower","identity_only_2x2")));
    static boolean knownRule(String id,int version) { return version==VERSION && ("unknown".equals(id) || IDS.contains(id)); }
    private static final java.util.Map<String,String> UNKNOWN_DAMAGE=new java.util.HashMap<>();
    static {
        for(String id:IDS)UNKNOWN_DAMAGE.put(id,id+"_damage_or_trigger_unknown");
        UNKNOWN_DAMAGE.put("unknown","unknown_damage_or_trigger_unknown");
    }
    static String unknownDamage(String id) { return UNKNOWN_DAMAGE.get(id); }
    static String ruleForKind(Match3Position.Kind kind) {
        switch(kind) {
            case ANIMAL:return "ordinary_uncovered_animal";case BIRD:return "magic_bird";
            case COIN:return "coin";case SNOW:return "snow";case ICEFLOWER:return "multistage_iceflower";
            case HONEY:return "single_clear_honey";case EGG:return "egg";case COOKIE:return "identity_only_2x2";
            case POISON:return "poison";case CHEST:return "chest";case FURBALL:return "furball";
            case INVINCIBLE_FURBALL:return "invincible_furball";case SNOWFLAKE:return "snowflake";
            case LEAF:return "leaf";case CLOUD:return "cloud";case FLOWER:return "flower_cluster";
            case EGG_SHELL:return "egg_shell";case ICE_BARRIER:return "ice_barrier";case NEST:return "chick_hatch_goal";
            case PENGUIN:return "penguin";case SNOW_BEAR:return "snow_bear";case SPRITE:return "sprite";
            case ONE_EYE:return "one_eye";case DOLPHIN:return "dolphin";case GOLD_POD:return "gold_pod";
            case KEY:return "key";case GEM:return "gem";case STAR:return "star";case JELLYFISH:return "jellyfish";
            case CANNON:return "cannon";case SHELL:return "shell";case PUFFER:return "puffer";
            case MAGIC_STONE:return "magic_stone";case DYE_EGG:return "dye_egg";case DYE_BABY:return "dye_baby";
            case STAR_BOTTLE:return "star_bottle";case ENERGY_BOTTLE:return "energy_bottle";
            case OCTOPUS:return "octopus";case CUTTLEFISH:return "cuttlefish";case MOVING_FLOOR:return "moving_floor";
            default:return "unknown";
        }
    }
    private Match3Rules() { }
}
