package io.infranexum.ddi.ipam.domain;

public enum NetworkKind { BLOCK,PREFIX,SUBNET; public String wireValue(){return name().toLowerCase(java.util.Locale.ROOT);} public static NetworkKind parse(String v){return valueOf(v.strip().toUpperCase(java.util.Locale.ROOT));} }
