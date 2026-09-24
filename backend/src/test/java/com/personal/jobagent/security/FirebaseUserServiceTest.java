package com.personal.jobagent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The provisioning path decides which local account a verified Firebase
 * identity maps to — and, crucially, when a brand-new account may be created at
 * all. The invite gate must be consulted on exactly that path and nowhere else:
 * an existing account re-signing in must never be blocked by registration
 * configuration.
 */
class FirebaseUserServiceTest {

    private static final String UID = "firebase-uid-abc";
    private static final String EMAIL = "Person@Example.com";
    private static final String NORMALISED_EMAIL = "person@example.com";

    private UserRepository userRepository;
    private com.personal.jobagent.profile.ProfileRepository profileRepository;
    private RegistrationGate registrationGate;
    private FirebaseUserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        profileRepository = mock(com.personal.jobagent.profile.ProfileRepository.class);
        registrationGate = mock(RegistrationGate.class);
        service = new FirebaseUserService(userRepository, profileRepository, registrationGate);
    }

    @Test
    void anAccountIsGivenAProfileBecauseOwnershipHangsOffIt() {
        UUID newId = UUID.randomUUID();
        when(registrationGate.evaluate("invite")).thenReturn(RegistrationGate.Decision.ALLOWED);
        when(userRepository.insertFirebaseUser(any(), eq(NORMALISED_EMAIL), eq("Person"), eq(UID))).thenReturn(newId);
        when(userRepository.findById(newId)).thenReturn(Optional.of(user(newId, UID, "FIREBASE", null)));
        // No profile yet, which is the brand-new-account case.
        when(profileRepository.findByUserId(newId)).thenReturn(Optional.empty());

        service.signIn(identity(UID, EMAIL, "Person"), "invite");

        // profiles is the root every user-owned table is scoped by, and the whole
        // API resolves the caller through it — an account without one cannot use
        // the product at all.
        verify(profileRepository).createProfile(eq(newId), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void signInDoesNotRecreateAnExistingProfile() {
        UserRecord existing = user(UUID.randomUUID(), UID, "FIREBASE", null);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(existing));
        when(profileRepository.findByUserId(existing.id())).thenReturn(Optional.of(
                mock(com.personal.jobagent.profile.ProfileRecord.class)));

        service.signIn(identity(UID, EMAIL, "Person"), null);

        verify(profileRepository, never()).createProfile(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anAlreadyLinkedIdentitySignsInWithoutConsultingTheGate() {
        UserRecord existing = user(UUID.randomUUID(), UID, "FIREBASE", null);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(existing));

        var result = service.signIn(identity(UID, EMAIL, "Person"), null);

        assertThat(result.created()).isFalse();
        assertThat(result.user()).isSameAs(existing);
        verify(registrationGate, never()).evaluate(any());
        verify(userRepository, never()).insertFirebaseUser(any(), anyString(), anyString(), anyString());
    }

    @Test
    void aKnownEmailIsLinkedToTheExistingAccountRatherThanCreatingANewOne() {
        UserRecord existing = user(UUID.randomUUID(), null, "LOCAL", "$argon2id$existing-hash");
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(NORMALISED_EMAIL)).thenReturn(Optional.of(existing));
        when(userRepository.linkFirebaseUid(existing.id(), UID, "Person")).thenReturn(true);
        when(userRepository.findById(existing.id())).thenReturn(Optional.of(existing));

        var result = service.signIn(identity(UID, EMAIL, "Person"), null);

        assertThat(result.created()).isFalse();
        // Linking must not be treated as registration, or an existing account
        // could be locked out by a misconfigured invite code.
        verify(registrationGate, never()).evaluate(any());
        verify(userRepository, never()).insertFirebaseUser(any(), anyString(), anyString(), anyString());
    }

    @Test
    void anUnverifiedEmailIsNeverLinkedToAnExistingAccount() {
        // Anyone can create a Firebase credential naming someone else's email
        // without proving they control it. Linking on the strength of the
        // address alone would hand over the existing account.
        UserRecord existing = user(UUID.randomUUID(), null, "LOCAL", "$argon2id$existing-hash");
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(NORMALISED_EMAIL)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.signIn(unverifiedIdentity(UID, EMAIL, "Person"), null))
                .isInstanceOf(FirebaseUserService.UnverifiedEmail.class);

        verify(userRepository, never()).linkFirebaseUid(any(), anyString(), anyString());
        verify(userRepository, never()).insertFirebaseUser(any(), anyString(), anyString(), anyString());
        verify(registrationGate, never()).evaluate(any());
    }

    @Test
    void anUnverifiedEmailCannotCreateANewAccountEither() {
        // The frontend only exchanges tokens after Firebase's verification
        // email has been followed, so an unverified token reaching this
        // endpoint means someone skipped that step. Refuse before the invite
        // gate and before the email lookup, so the refusal is identical
        // whether or not a local account exists — no enumeration signal, and
        // no local account minted from an unproven address.
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(NORMALISED_EMAIL)).thenReturn(Optional.empty());
        when(registrationGate.evaluate("invite")).thenReturn(RegistrationGate.Decision.ALLOWED);

        assertThatThrownBy(() -> service.signIn(unverifiedIdentity(UID, EMAIL, "Person"), "invite"))
                .isInstanceOf(FirebaseUserService.UnverifiedEmail.class);

        verify(userRepository, never()).findByEmail(anyString());
        verify(registrationGate, never()).evaluate(any());
        verify(userRepository, never()).insertFirebaseUser(any(), anyString(), anyString(), anyString());
    }

    @Test
    void anAlreadyLinkedAccountKeepsWorkingEvenIfTheTokenIsUnverified() {
        // A user who changed their email inside Firebase (or re-secured the
        // account) still signs in through the existing UID link; verification
        // is required only for claiming or creating accounts.
        UserRecord existing = user(UUID.randomUUID(), UID, "FIREBASE", null);
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(existing));
        when(profileRepository.findByUserId(existing.id())).thenReturn(Optional.of(
                mock(com.personal.jobagent.profile.ProfileRecord.class)));

        var result = service.signIn(unverifiedIdentity(UID, EMAIL, "Person"), null);

        assertThat(result.created()).isFalse();
        assertThat(result.user()).isSameAs(existing);
        verify(registrationGate, never()).evaluate(any());
    }

    @Test
    void aNewIdentityWithAPermittedRegistrationCreatesAFirebaseBackedAccount() {
        UUID newId = UUID.randomUUID();
        when(registrationGate.evaluate("invite")).thenReturn(RegistrationGate.Decision.ALLOWED);
        when(userRepository.insertFirebaseUser(any(), eq(NORMALISED_EMAIL), eq("Person"), eq(UID))).thenReturn(newId);
        when(userRepository.findById(newId)).thenReturn(Optional.of(user(newId, UID, "FIREBASE", null)));

        var result = service.signIn(identity(UID, EMAIL, "Person"), "invite");

        assertThat(result.created()).isTrue();
        assertThat(result.user().id()).isEqualTo(newId);
    }

    @Test
    void aNewIdentityWithoutARequiredInviteCodeIsRefusedAndNothingIsWritten() {
        when(registrationGate.evaluate(null)).thenReturn(RegistrationGate.Decision.MISSING_CODE);

        assertThatThrownBy(() -> service.signIn(identity(UID, EMAIL, "Person"), null))
                .isInstanceOf(FirebaseUserService.RegistrationRefused.class)
                .hasMessageContaining("invite code is required");

        verify(userRepository, never()).insertFirebaseUser(any(), anyString(), anyString(), anyString());
    }

    @Test
    void aNewIdentityWithTheWrongInviteCodeIsRefused() {
        when(registrationGate.evaluate("wrong")).thenReturn(RegistrationGate.Decision.INVALID_CODE);

        assertThatThrownBy(() -> service.signIn(identity(UID, EMAIL, "Person"), "wrong"))
                .isInstanceOf(FirebaseUserService.RegistrationRefused.class);

        verify(userRepository, never()).insertFirebaseUser(any(), anyString(), anyString(), anyString());
    }

    @Test
    void aNewIdentityIsRefusedWhenTheServerHasNoInviteCodeConfigured() {
        when(registrationGate.evaluate(null)).thenReturn(RegistrationGate.Decision.NOT_CONFIGURED);

        assertThatThrownBy(() -> service.signIn(identity(UID, EMAIL, "Person"), null))
                .isInstanceOf(FirebaseUserService.RegistrationRefused.class);

        verify(userRepository, never()).insertFirebaseUser(any(), anyString(), anyString(), anyString());
    }

    @Test
    void anIdentityWithNoEmailCannotBecomeAnAccount() {
        assertThatThrownBy(() -> service.signIn(identity(UID, null, "Person"), "invite"))
                .isInstanceOf(FirebaseUserService.IdentityIncomplete.class);

        verify(userRepository, never()).insertFirebaseUser(any(), anyString(), anyString(), anyString());
    }

    @Test
    void aMissingDisplayNameFallsBackToTheEmailLocalPart() {
        UUID newId = UUID.randomUUID();
        when(registrationGate.evaluate("invite")).thenReturn(RegistrationGate.Decision.ALLOWED);
        when(userRepository.insertFirebaseUser(any(), eq(NORMALISED_EMAIL), eq("person"), eq(UID))).thenReturn(newId);
        when(userRepository.findById(newId)).thenReturn(Optional.of(user(newId, UID, "FIREBASE", null)));

        service.signIn(identity(UID, EMAIL, "   "), "invite");

        verify(userRepository).insertFirebaseUser(any(), eq(NORMALISED_EMAIL), eq("person"), eq(UID));
    }

    @Test
    void emailIsNormalisedBeforeLookupSoCasingCannotCreateADuplicateAccount() {
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.empty());
        when(userRepository.findByEmail(NORMALISED_EMAIL)).thenReturn(Optional.empty());
        when(registrationGate.evaluate("invite")).thenReturn(RegistrationGate.Decision.ALLOWED);
        UUID newId = UUID.randomUUID();
        when(userRepository.insertFirebaseUser(any(), eq(NORMALISED_EMAIL), anyString(), eq(UID))).thenReturn(newId);
        when(userRepository.findById(newId)).thenReturn(Optional.of(user(newId, UID, "FIREBASE", null)));

        service.signIn(identity(UID, "  PERSON@EXAMPLE.COM  ", "Person"), "invite");

        verify(userRepository).findByEmail(NORMALISED_EMAIL);
        verify(userRepository).insertFirebaseUser(any(), eq(NORMALISED_EMAIL), anyString(), eq(UID));
    }

    private static FirebaseTokenVerifier.VerifiedIdentity identity(String uid, String email, String name) {
        return new FirebaseTokenVerifier.VerifiedIdentity(uid, email, name, true);
    }

    private static FirebaseTokenVerifier.VerifiedIdentity unverifiedIdentity(String uid, String email, String name) {
        return new FirebaseTokenVerifier.VerifiedIdentity(uid, email, name, false);
    }

    private static UserRecord user(UUID id, String firebaseUid, String provider, String passwordHash) {
        return new UserRecord(id, "person@example.com", passwordHash, "Person", firebaseUid, provider);
    }
}
