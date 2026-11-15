package io.github.myungjoo.nntesla.protocol

import com.google.protobuf.ByteString
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.keys.Keys
import com.tesla.generated.universalmessage.UniversalMessage.Destination
import com.tesla.generated.universalmessage.UniversalMessage.Domain
import com.tesla.generated.universalmessage.UniversalMessage.RoutableMessage
import com.tesla.generated.universalmessage.UniversalMessage.SessionInfoRequest
import com.tesla.generated.vcsec.Vcsec

/** `GetVehicleData` categories available from the infotainment domain. */
enum class StateCategory {
    CHARGE, CLIMATE, DRIVE, LOCATION, CLOSURES, CHARGE_SCHEDULE, PRECONDITIONING_SCHEDULE,
    TIRE_PRESSURE, MEDIA, MEDIA_DETAIL, SOFTWARE_UPDATE, PARENTAL_CONTROLS,
}

object Messages {
    fun sessionInfoRequest(
        domain: Domain,
        publicKey: ByteArray,
        routingAddress: ByteArray,
        uuid: ByteArray,
    ): RoutableMessage = RoutableMessage.newBuilder()
        .setToDestination(Destination.newBuilder().setDomain(domain))
        .setFromDestination(Destination.newBuilder().setRoutingAddress(ByteString.copyFrom(routingAddress)))
        .setSessionInfoRequest(SessionInfoRequest.newBuilder().setPublicKey(ByteString.copyFrom(publicKey)))
        .setUuid(ByteString.copyFrom(uuid))
        .build()

    /** Encoded `CarServer.Action` requesting the given categories in one `GetVehicleData`. */
    fun getVehicleData(categories: Set<StateCategory>): ByteArray {
        require(categories.isNotEmpty())
        val request = CarServer.GetVehicleData.newBuilder()
        for (category in categories) {
            when (category) {
                StateCategory.CHARGE -> request.setGetChargeState(CarServer.GetChargeState.getDefaultInstance())
                StateCategory.CLIMATE -> request.setGetClimateState(CarServer.GetClimateState.getDefaultInstance())
                StateCategory.DRIVE -> request.setGetDriveState(CarServer.GetDriveState.getDefaultInstance())
                StateCategory.LOCATION -> request.setGetLocationState(CarServer.GetLocationState.getDefaultInstance())
                StateCategory.CLOSURES -> request.setGetClosuresState(CarServer.GetClosuresState.getDefaultInstance())
                StateCategory.CHARGE_SCHEDULE ->
                    request.setGetChargeScheduleState(CarServer.GetChargeScheduleState.getDefaultInstance())
                StateCategory.PRECONDITIONING_SCHEDULE ->
                    request.setGetPreconditioningScheduleState(CarServer.GetPreconditioningScheduleState.getDefaultInstance())
                StateCategory.TIRE_PRESSURE ->
                    request.setGetTirePressureState(CarServer.GetTirePressureState.getDefaultInstance())
                StateCategory.MEDIA -> request.setGetMediaState(CarServer.GetMediaState.getDefaultInstance())
                StateCategory.MEDIA_DETAIL -> request.setGetMediaDetailState(CarServer.GetMediaDetailState.getDefaultInstance())
                StateCategory.SOFTWARE_UPDATE ->
                    request.setGetSoftwareUpdateState(CarServer.GetSoftwareUpdateState.getDefaultInstance())
                StateCategory.PARENTAL_CONTROLS ->
                    request.setGetParentalControlsState(CarServer.GetParentalControlsState.getDefaultInstance())
            }
        }
        return CarServer.Action.newBuilder()
            .setVehicleAction(CarServer.VehicleAction.newBuilder().setGetVehicleData(request))
            .build()
            .toByteArray()
    }

    /**
     * Raw (not RoutableMessage-wrapped) VCSEC request asking the vehicle to add [publicKey] to its
     * whitelist. The vehicle then waits for an owner keycard tap on the center console.
     */
    fun addKeyRequest(publicKey: ByteArray, role: Keys.Role): ByteArray {
        val unsigned = Vcsec.UnsignedMessage.newBuilder()
            .setWhitelistOperation(
                Vcsec.WhitelistOperation.newBuilder()
                    .setAddKeyToWhitelistAndAddPermissions(
                        Vcsec.PermissionChange.newBuilder()
                            .setKey(Vcsec.PublicKey.newBuilder().setPublicKeyRaw(ByteString.copyFrom(publicKey)))
                            .setKeyRole(role),
                    )
                    .setMetadataForKey(
                        Vcsec.KeyMetadata.newBuilder()
                            .setKeyFormFactor(Vcsec.KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE),
                    ),
            )
            .build()
        return Vcsec.ToVCSECMessage.newBuilder()
            .setSignedMessage(
                Vcsec.SignedMessage.newBuilder()
                    .setProtobufMessageAsBytes(unsigned.toByteString())
                    .setSignatureType(Vcsec.SignatureType.SIGNATURE_TYPE_PRESENT_KEY),
            )
            .build()
            .toByteArray()
    }

    fun parseCarServerResponse(payload: ByteArray): CarServer.Response = CarServer.Response.parseFrom(payload)
}
