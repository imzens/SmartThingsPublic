import physicalgraph.zigbee.clusters.iaszone.ZoneStatus

metadata {
	definition (name: "SYLVANIA Smart Motion/Temperature Sensor", namespace: "ledvanceDH", author: "Ledvance") {
		capability "Configuration"
		capability "Motion Sensor"
		capability "Battery"
        capability "Temperature Measurement"
		capability "Refresh"
		capability "Health Check"
		capability "Sensor"

		command "enrollResponse"




        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0020,0402,0500,0B05", outCluster: "0019", manufacturer: "CentraLite", model: "Motion Sensor-A", deviceJoinName: "SYLVANIA Smart Motion Sensor"
	}

	simulator {
		for (int i = 0; i <= 100; i += 10) {
			status "${i}F": "temperature: $i F"
		}
        
        status 'H 40': 'catchall: 0104 FC45 01 01 0140 00 D9B9 00 04 C2DF 0A 01 000021780F'
		status 'H 45': 'catchall: 0104 FC45 01 01 0140 00 D9B9 00 04 C2DF 0A 01 0000218911'
		status 'H 57': 'catchall: 0104 FC45 01 01 0140 00 4E55 00 04 C2DF 0A 01 0000211316'
		status 'H 53': 'catchall: 0104 FC45 01 01 0140 00 20CD 00 04 C2DF 0A 01 0000219814'
		status 'H 43':  'read attr - raw: BF7601FC450C00000021A410, dni: BF76, endpoint: 01, cluster: FC45, size: 0C, attrId: 0000, result: success, encoding: 21, value: 10a4'
		}
        




	preferences {
		input title: "Temperature Offset", description: "This feature allows you to correct any incorrect temperature readings by selecting a value to offset the temperature by. For example, if your sensor consistently reports a temperature that's 2 degrees too warm, you'd enter \"-2\". If 4 degrees too cold, enter \"+4\".", displayDuringSetup: false, type: "paragraph", element: "paragraph"
		input "tempOffset", "number", title: "Degrees", description: "Adjust temperature by this many degrees", range: "*..*", displayDuringSetup: false
	}

	tiles(scale: 2) {
		multiAttributeTile(name:"motion", type: "generic", width: 6, height: 4){
			tileAttribute ("device.motion", key: "PRIMARY_CONTROL") {
				attributeState "active", label:'motion', icon:"st.motion.motion.active", backgroundColor:"#53a7c0"
				attributeState "inactive", label:'no motion', icon:"st.motion.motion.inactive", backgroundColor:"#ffffff"
			}
		}
		valueTile("temperature", "device.temperature", width: 2, height: 2) {
			state("temperature", label:'${currentValue}°', unit:"F",
				backgroundColors:[
					[value: 31, color: "#153591"],
					[value: 44, color: "#1e9cbb"],
					[value: 59, color: "#90d2a7"],
					[value: 74, color: "#44b621"],
					[value: 84, color: "#f1d801"],
					[value: 95, color: "#d04e00"],
					[value: 96, color: "#bc2323"]
				]
			)
		}
        valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "battery", label:'${currentValue}% battery'
		}
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
		}

		main(["motion", "temperature"])
		details(["motion", "temperature", "battery", "refresh"])
	}
}

def parse(String description) {
	log.debug "description: $description"


	Map map = [:]
	if (description?.startsWith('catchall:')) {
		map = parseCatchAllMessage(description)
	}
	else if (description?.startsWith('read attr -')) {
		map = parseReportAttributeMessage(description)
	}
	else if (description?.startsWith('zone status')) {
		map = parseIasMessage(description)
	}
    else if (description?.startsWith('temperature')) {
    	map = parseCustomMessage(description)
    }


	log.debug "Parse returned $map"
	def result = map ? createEvent(map) : [:]


	if (description?.startsWith('enroll request')) {
		List cmds = enrollResponse()
		log.debug "enroll response: ${cmds}"
		result = cmds?.collect { new physicalgraph.device.HubAction(it) }
	}
	return result
}

private Map parseCatchAllMessage(String description) {
	Map resultMap = [:]
	def cluster = zigbee.parse(description)
	if (shouldProcessMessage(cluster)) {
		switch(cluster.clusterId) {
			case 0x0406:
				// 0x07 - configure reporting
				if (cluster.command != 0x07) {
					log.debug 'motion'
					resultMap.name = 'motion'
				}
				break
             case 0x0001:
				// 0x07 - configure reporting
				if (cluster.command != 0x07) {
					resultMap = getBatteryResult(cluster.data.last())
				}
                break

            case 0x0402:
				if (cluster.command == 0x07) {
					if (cluster.data[0] == 0x00){
						log.debug "TEMP REPORTING CONFIG RESPONSE" + cluster
						resultMap = [name: "checkInterval", value: 60 * 12, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID]]
					}
					else {
						log.warn "TEMP REPORTING CONFIG FAILED- error code:${cluster.data[0]}"
					}
				}
				else {
					// temp is last 2 data values. reverse to swap endian
					String temp = cluster.data[-2..-1].reverse().collect { cluster.hex1(it) }.join()
					def value = getTemperature(temp)
					resultMap = getTemperatureResult(value)
				}
				break
		}
	}








	return resultMap
}

private boolean shouldProcessMessage(cluster) {
	// 0x0B is default response indicating message got through

	boolean ignoredMessage = cluster.profileId != 0x0104 ||
		cluster.command == 0x0B ||

		(cluster.data.size() > 0 && cluster.data.first() == 0x3e)
	return !ignoredMessage
}

private Map parseReportAttributeMessage(String description) {
	Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
	log.debug "Desc Map: $descMap"


	Map resultMap = [:]
	if (descMap.cluster == "0406" && descMap.attrId == "0000") {
		def value = descMap.value.endsWith("01") ? "active" : "inactive"
		resultMap = getMotionResult(value)
	}
    else if (descMap.cluster == "0402" && descMap.attrId == "0000") {
		def value = getTemperature(descMap.value)
		resultMap = getTemperatureResult(value)
	}
	else if (descMap.cluster == "0001" && descMap.attrId == "0020") {
		resultMap = getBatteryResult(Integer.parseInt(descMap.value, 16))
	}






	return resultMap
}


private Map parseCustomMessage(String description) {
	Map resultMap = [:]

	def value = zigbee.parseHATemperatureValue(description, "temperature: ", getTemperatureScale())
	resultMap = getTemperatureResult(value)
	

	return resultMap
}

private Map parseIasMessage(String description) {
	ZoneStatus zs = zigbee.parseZoneStatus(description)









	// Some sensor models that use this DTH use alarm1 and some use alarm2 to signify motion
	return (zs.isAlarm1Set() || zs.isAlarm2Set()) ? getMotionResult('active') : getMotionResult('inactive')
}




private Map getMotionResult(value) {
	log.debug 'motion'
	String descriptionText = value == 'active' ? "{{ device.displayName }} detected motion" : "{{ device.displayName }} motion has stopped"
	return [
		name: 'motion',
		value: value,
		descriptionText: descriptionText,
        translatable: true
	]

























}

def getTemperature(value) {
	def celsius = Integer.parseInt(value, 16).shortValue() / 100
	if(getTemperatureScale() == "C"){
		return celsius
	} else {
		return celsiusToFahrenheit(celsius) as Integer
	}
}

private Map getBatteryResult(rawValue) {
	log.debug 'Battery'
	def linkText = getLinkText(device)



    def result = [:]




	def volts = rawValue / 10
	if (!(rawValue == 0 || rawValue == 255)) {
		def minVolts = 2.1
		def maxVolts = 3.0
		def pct = (volts - minVolts) / (maxVolts - minVolts)
		def roundedPct = Math.round(pct * 100)
		if (roundedPct <= 0)
			roundedPct = 1
		result.value = Math.min(100, roundedPct)
		result.descriptionText = "${linkText} battery was ${result.value}%"
		result.name = 'battery'














	}

	return result
}

private Map getTemperatureResult(value) {
	log.debug 'TEMP'
	def linkText = getLinkText(device)
	if (tempOffset) {
		def offset = tempOffset as int
		def v = value as int
		value = v + offset
	}
	def descriptionText = "${linkText} was ${value}°${temperatureScale}"
	return [
		name: 'temperature',
		value: value,
		descriptionText: descriptionText,
		unit: temperatureScale
	]
}

private String parseValue(String description) {
	return zigbee.parseHATemperatureValue(description, "temperature: ", getTemperatureScale())
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
	return zigbee.readAttribute(0x001, 0x0020) // Read the Battery Level









}

def refresh() {
	log.debug "refresh called"
	def refreshCmds = [
		"st rattr 0x${device.deviceNetworkId} 1 0x402 0", "delay 200",
		"st rattr 0x${device.deviceNetworkId} 1 1 0x20", "delay 200"
	]
    

	return refreshCmds + enrollResponse()
}

def configure() {
	// Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
	// enrolls with default periodic reporting until newer 5 min interval is confirmed
	sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 1 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])



	// temperature minReportTime 30 seconds, maxReportTime 5 min. Reporting interval if no activity
	// battery minReport 30 seconds, maxReportTime 6 hrs by default
	return refresh() + zigbee.batteryConfig() + zigbee.temperatureConfig(30, 300) // send refresh cmds as part of config













}

def enrollResponse() {
	log.debug "Sending enroll response"
	String zigbeeEui = swapEndianHex(device.hub.zigbeeEui)
	[
		//Resending the CIE in case the enroll request is sent before CIE is written
		"zcl global write 0x500 0x10 0xf0 {${zigbeeEui}}", "delay 200",
		"send 0x${device.deviceNetworkId} 1 ${endpointId}", "delay 500",
		//Enroll Response
		"raw 0x500 {01 23 00 00 00}",
		"send 0x${device.deviceNetworkId} 1 1", "delay 200"
	]
}

private getEndpointId() {
	new BigInteger(device.endpointId, 16).toString()
}

private hex(value) {
	new BigInteger(Math.round(value).toString()).toString(16)
}

private String swapEndianHex(String hex) {
	reverseArray(hex.decodeHex()).encodeHex()
}

private byte[] reverseArray(byte[] array) {
	int i = 0;
	int j = array.length - 1;
	byte tmp;
	while (j > i) {
		tmp = array[j];
		array[j] = array[i];
		array[i] = tmp;
		j--;
		i++;
	}
	return array
}