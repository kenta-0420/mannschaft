package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 03 API正本のRanchSettings。BIGINTはdecimal string、日時はUTC瞬間。 */
public record RanchSettings(boolean isVisible, String viewMode, RenderStyle renderStyle, MotionMode motionMode, boolean isSoundEnabled, int soundVolume, String version) { }
