p = 'android/app/src/main/AndroidManifest.xml'
s = open(p, encoding='utf-8').read()
if 'xmlns:tools' not in s:
    s = s.replace('<manifest ', '<manifest xmlns:tools="http://schemas.android.com/tools" ', 1)
names = ['SYSTEM_ALERT_WINDOW', 'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_SPECIAL_USE',
         'RECEIVE_BOOT_COMPLETED', 'VIBRATE', 'POST_NOTIFICATIONS', 'SCHEDULE_EXACT_ALARM']
perms = ''.join('\n    <uses-permission android:name="android.permission.%s"/>' % n for n in names)
perms += '\n    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions"/>'
comp = '''
        <service android:name=".BlockerService" android:exported="false" android:foregroundServiceType="specialUse">
            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="app_blocker"/>
        </service>
        <receiver android:name=".BootReceiver" android:exported="true">
            <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter>
        </receiver>
    '''
q = '<queries><intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent></queries>\n    '
s = s.replace('<application', q + '<application', 1)
s = s.replace('</application>', comp + '</application>', 1)
s = s.replace('</manifest>', perms + '\n</manifest>', 1)
open(p, 'w', encoding='utf-8').write(s)
print('manifest ok')
