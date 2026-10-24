package com.personal.jobagent.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class JdbcUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final OperatorAccessPolicy operatorPolicy;

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcUserDetailsService(UserRepository userRepository, OperatorAccessPolicy operatorPolicy) {
        this.userRepository = userRepository;
        this.operatorPolicy = operatorPolicy;
    }

    public JdbcUserDetailsService(UserRepository userRepository) {
        this(userRepository, new OperatorAccessPolicy(""));
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .map(user -> new AppUserDetails(user, operatorPolicy.isOperator(user)))
                .orElseThrow(() -> new UsernameNotFoundException("No user with that email"));
    }
}
