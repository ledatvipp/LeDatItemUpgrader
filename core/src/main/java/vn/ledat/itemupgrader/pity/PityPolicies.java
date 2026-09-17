package vn.ledat.itemupgrader.pity;
import java.util.*;
/** Disjoint policies are required; no priority/fallback ambiguity. Changing epoch creates a deliberate fresh scope. */
public record PityPolicies(boolean enabled,List<PityPolicy> policies) {
    public PityPolicies {
        policies=List.copyOf(policies);
        if(policies.size()>64)throw new IllegalArgumentException("too many pity policies");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<policies.size();i++) {
            var a=policies.get(i);if(!ids.add(a.id()))throw new IllegalArgumentException("duplicate pity policy");
            for(int j=0;j<i;j++) {var b=policies.get(j);
                if(!Collections.disjoint(a.paths(),b.paths())&&!Collections.disjoint(a.targets(),b.targets())&&!Collections.disjoint(a.profiles(),b.profiles()))
                    throw new IllegalArgumentException("overlapping pity policies");
            }
        }
    }
    public static PityPolicies disabled(){return new PityPolicies(false,List.of());}
    public Optional<PityPolicy> find(String path,String target,String profile) {
        if(!enabled)return Optional.empty();
        return policies.stream().filter(p->p.matches(path,target,profile)).findFirst();
    }
}
