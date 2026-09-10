package com.changeguard;
import com.changeguard.model.CloudResource;
import com.changeguard.rules.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RuleEdgeCaseTest {
    private ArchitectureRule rule(String id) throws Exception { return TestSupport.catalog().rules().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(); }
    @ParameterizedTest @CsvSource({"Allow,*,*,false","Deny,*,*,true","Allow,s3:GetObject,*,true","Allow,s3:*,*,false"})
    void iamStatements(String effect,String action,String resource,boolean passed) throws Exception {
        var r=new CloudResource("policy","AWS::IAM::Policy",Map.of("PolicyDocument",Map.of("Statement",Map.of("Effect",effect,"Action",action,"Resource",resource))));
        assertThat(rule("SEC-IAM-001").evaluate(r).passed()).isEqualTo(passed);
    }
    @Test void notActionAndArrayWildcardsAreDetected() throws Exception {
        var r=new CloudResource("policy","AWS::IAM::Policy",Map.of("PolicyDocument",Map.of("Statement",List.of(Map.of("Effect","Allow","NotAction","iam:DeleteUser","Resource",List.of("*"))))));
        assertThat(rule("SEC-IAM-001").evaluate(r).passed()).isFalse();
    }
    @Test void unknownIsNotReportedAsPolicyViolation() throws Exception {
        var r=new CloudResource("db","AWS::RDS::DBInstance",Map.of("StorageEncrypted",Map.of("Fn::If",List.of("Prod",true,false))));
        assertThat(rule("SEC-RDS-001").evaluate(r).outcome()).isEqualTo(RuleResult.Outcome.UNKNOWN);
    }
    @ParameterizedTest @CsvSource({"0.0.0.0/0,22,false","::/0,3389,false","10.0.0.0/8,22,true","0.0.0.0/0,443,true"})
    void ingress(String cidr,int port,boolean passed) throws Exception {
        var r=new CloudResource("sg","AWS::EC2::SecurityGroup",Map.of("SecurityGroupIngress",List.of(Map.of(cidr.contains(":")?"CidrIpv6":"CidrIp",cidr,"IpProtocol","tcp","FromPort",port,"ToPort",port))));
        assertThat(rule("SEC-EC2-001").evaluate(r).passed()).isEqualTo(passed);
    }
    @Test void catalogHasUniqueVersionedMetadataAcrossSixPillars() throws Exception {
        var rules=TestSupport.catalog().rules();
        assertThat(rules).hasSize(42);
        assertThat(rules.stream().map(ArchitectureRule::pillar).distinct()).hasSize(6);
        assertThat(rules.stream().flatMap(r -> r.definition().resourceTypes().stream()).distinct()).hasSize(18);
    }
}
