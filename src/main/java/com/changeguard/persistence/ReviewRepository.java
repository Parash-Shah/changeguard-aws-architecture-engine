package com.changeguard.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ReviewRepository extends JpaRepository<ReviewEntity, UUID> { Optional<ReviewEntity> findByRequestKey(String key); }
