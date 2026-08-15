package io.infranexum.ddi.ipam.domain;

public enum IpamStatus { DRAFT,ACTIVE,RESERVED,DEPRECATED,RETIRED; public String wireValue(){return name().toLowerCase(java.util.Locale.ROOT);} public static IpamStatus parse(String value){return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));} }
