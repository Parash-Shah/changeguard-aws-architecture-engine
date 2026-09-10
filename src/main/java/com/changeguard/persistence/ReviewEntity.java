package com.changeguard.persistence;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="review")
public class ReviewEntity {
    @Id public UUID id;
    @Column(nullable=false) public Instant createdAt;
    @Column(unique=true, length=200) public String requestKey;
    @Column(nullable=false, length=64) public String requestHash;
    @Column(nullable=false, length=32) public String format;
    @Column(nullable=false, columnDefinition="text") public String report;
    @Column(nullable=false, columnDefinition="text") public String snapshot;
    @Column(nullable=false, columnDefinition="text") public String suppressions;
    public ReviewEntity() { }
}
