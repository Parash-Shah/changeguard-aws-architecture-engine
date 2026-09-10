package com.changeguard.parser;
import com.changeguard.model.CloudResource;
import java.util.List;
public interface InfrastructureParser { List<CloudResource> parse(String template); }
