@rem -alias somealias 
keytool -genkey -v -keystore unimportant.keystore -storepass somepassstore -keypass somepasskey -keyalg PKCS12	 -keysize 2048 -validity 10000
@pause
