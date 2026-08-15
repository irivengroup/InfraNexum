package io.infranexum.dcim.physical.domain;

import java.util.Locale;

/** Physical connector role declared by an equipment model. */
public enum PortKind { NETWORK, POWER, CONSOLE, FIBER, OTHER;
    public String wireValue(){return name().toLowerCase(Locale.ROOT);} }
