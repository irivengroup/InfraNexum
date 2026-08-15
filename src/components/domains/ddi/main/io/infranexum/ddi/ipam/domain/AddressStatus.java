package io.infranexum.ddi.ipam.domain;

public enum AddressStatus { ALLOCATED,RESERVED,DEPRECATED,RELEASED; public String wireValue(){return name().toLowerCase(java.util.Locale.ROOT);} public static AddressStatus parse(String value){return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));} }
