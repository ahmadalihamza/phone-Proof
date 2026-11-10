"""Source of truth for versioned, capability-gated definitions and documentation."""
import json
from pathlib import Path
from collections import Counter

ROOT = Path(__file__).resolve().parents[1]
definitions = []


def add(id, title, category, handler, *, mode='AUTOMATIC', weight=0, critical=False,
        capabilities=(), permissions=(), api=29, params=None, enabled=True,
        expected='Public API returned the documented value; information only', fail='No hardware failure inferred from capability metadata',
        description='', timeout=15, version=1):
    definitions.append(dict(id=id, title=title, category=category, version=version, mode=mode,
        implementation='ENABLED' if enabled else 'DISABLED', min_api=api,
        capabilities=list(capabilities), permissions=list(permissions), weight=weight,
        critical=critical, pass_criteria=expected, fail_criteria=fail,
        not_supported='Required hardware is not exposed or this Android API is unavailable',
        not_accessible='Permission denied, platform restriction, missing field, or service unavailable',
        evidence_type='USER_OBSERVATION' if mode == 'USER_CONFIRMED' else 'PLATFORM_MEASUREMENT',
        handler=handler, parameters=params or {}, timeout_seconds=timeout,
        description=description or title,
        scoring_type='FUNCTIONAL' if weight else 'INFORMATION'))


system_fields = {
 'manufacturer':'Manufacturer', 'brand':'Brand', 'model':'Model', 'product':'Product', 'device':'Device codename',
 'release':'Android release', 'api':'Android API level', 'build_id':'Build ID', 'fingerprint':'Build fingerprint',
 'security_patch':'Security patch date', 'abis':'Supported ABIs', 'processors':'Available processor count',
 'ram_total':'System RAM', 'ram_available':'Available RAM', 'low_memory':'Memory pressure',
 'storage_total':'Internal storage total', 'storage_available':'Internal storage available', 'storage_used':'Storage utilization',
 'security_patch_age':'Security patch age (days)', 'developer_options':'Developer options signal',
 'debuggable':'App debug build signal', 'emulator':'Emulator risk indicators', 'root_risk':'Root risk indicators',
 'webview':'WebView runtime', 'gles':'OpenGL ES declared version', 'clock':'Clock plausibility signal',
 'encryption':'Device storage encryption status', 'rotation_lock':'Rotation lock setting',
}
for key, title in system_fields.items():
    add('system.'+key, title, 'system', 'system', params={'field':key})

features = {
 'verified_boot':'Verified Boot feature', 'strongbox':'StrongBox capability', 'fingerprint':'Fingerprint feature',
 'face':'Face biometric feature', 'vulkan':'Vulkan feature', 'usb_host':'USB host feature', 'nfc':'NFC feature',
 'nfc_hce':'NFC HCE feature', 'uwb':'UWB feature', 'telephony':'Telephony feature', 'esim':'eSIM feature',
 'bluetooth':'Bluetooth feature', 'ble':'Bluetooth LE feature', 'wifi':'Wi-Fi feature', 'gps':'GPS feature',
 'microphone':'Microphone feature', 'touchscreen':'Touchscreen feature', 'multitouch':'Multitouch feature',
}
for key, title in features.items():
    add('capability.'+key, title, 'security' if key in ('verified_boot','strongbox','fingerprint','face') else 'connectivity', 'feature', params={'field':key})
add('security.keystore', 'Android Keystore key round trip', 'security', 'keystore', expected='Generated key encrypts and decrypts a challenge; hardware backing reported separately')
add('security.play_integrity','Play Integrity verdict','security','external',enabled=False,description='Requires Google Play project and server token verification; unavailable until configured')

for key,title in {'resolution':'Active resolution','density':'Display density','refresh':'Active refresh rate','modes':'Supported display modes','hdr':'HDR types','wide_color':'Wide color gamut','state':'Screen power state'}.items():
    add('display.'+key,title,'display','display',params={'field':key})

guided_display = {
 'red':'Red pixels','green':'Green pixels','blue':'Blue pixels','white':'White uniformity','black':'Black uniformity','gray':'Gray uniformity',
 'gradient':'Color gradient / banding','grayscale':'Grayscale gradient','dead_pixels':'Dead-pixel inspection','stuck_pixels':'Stuck-pixel inspection',
 'lines':'Display line artifacts','discoloration':'Discoloration','burn_in':'Burn-in / image retention','flicker':'Visible flicker','edges':'Edge discoloration',
 'brightness_min':'Minimum brightness response','brightness_max':'Maximum brightness response',
}
for key,title in guided_display.items():
    add('display.'+key,title,'display','display_guided',mode='USER_CONFIRMED',weight=1,critical=key=='lines',params={'pattern':key},expected='Inspector sees expected pattern without the specified defect',fail='Inspector confirms visible defect',description='Inspect the full-screen pattern. This is a human observation, not calibrated panel certification.',timeout=120)

touch = {'single':'Single touch','grid':'Full touch grid coverage','edges':'Edge coverage','corners':'Corner coverage','multitouch':'Two simultaneous touches','three_finger':'Three simultaneous touches','drag':'Drag continuity','horizontal':'Horizontal swipe','vertical':'Vertical swipe','diagonal':'Diagonal drag','long_press':'Long press','rapid_tap':'Rapid taps','interval':'Observed touch event interval','jitter':'Stationary touch jitter'}
for key,title in touch.items():
    add('touch.'+key,title,'touch','touch',mode='GUIDED_AUTOMATIC',weight=0 if key in ('interval','jitter') else 2,critical=key=='grid',capabilities=('touchscreen',),params={'test':key},timeout=90,expected='Required touch event or coverage threshold observed',fail='User confirms dead area after completing the guided attempt',description='Only observed MotionEvents are used. Timeout without sufficient gestures is inconclusive. Event intervals are not official hardware sampling rates.')

battery_fields = {'level':'Charge percentage','status':'Charging status','plugged':'Power source','temperature':'Battery temperature','voltage':'Battery voltage','health':'Android battery health enum','technology':'Battery technology','present':'Battery present','charge_counter':'Charge counter','current_now':'Instantaneous battery current','current_average':'Average battery current','energy':'Remaining energy counter','cycle_count':'Exposed battery cycle count','low':'Battery-low broadcast state'}
for key,title in battery_fields.items():
    add('battery.'+key,title,'battery','battery',api=34 if key=='cycle_count' else 29,params={'field':key},description='BatteryManager/broadcast value. Current sign and meaning vary by device. No battery state-of-health percentage or charging wattage is inferred.')
for key,title in [('connect','Connect charger'),('disconnect','Disconnect charger')]:
    add('charging.'+key,title,'charging','charging',mode='GUIDED_AUTOMATIC',weight=2,params={'event':key},timeout=45,expected='Observed plugged-state transition after test starts',fail='No definitive hardware failure is inferred from timeout')
for key,title,api in [('status','System thermal status',29),('headroom','Thermal headroom',30)]:
    add('thermal.'+key,title,'thermal','thermal',params={'field':key},api=api)

sensors = {'accelerometer':1,'magnetometer':2,'gyroscope':4,'light':5,'barometer':6,'proximity':8,'gravity':9,'linear_acceleration':10,'rotation_vector':11,'humidity':12,'ambient_temperature':13,'game_rotation':15,'step_detector':18,'step_counter':19,'hinge':36}
for name,type_id in sensors.items():
    for field in ('name','vendor','version','range','resolution','power','min_delay','reporting_mode'):
        add(f'sensor.{name}.{field}',f'{name.replace("_"," ").title()} {field.replace("_"," ")}', 'sensors','sensor_metadata',capabilities=(f'sensor.{name}',),params={'type':str(type_id),'field':field},api=30 if name=='hinge' else 29)
    for field in ('response','continuity','variation'):
        add(f'sensor.{name}.{field}',f'{name.replace("_"," ").title()} {field}', 'sensors','sensor_sample',mode='GUIDED_AUTOMATIC',weight=1 if field=='response' else 0,capabilities=(f'sensor.{name}',),permissions=('android.permission.ACTIVITY_RECOGNITION',) if name.startswith('step_') else (),params={'type':str(type_id),'field':field},timeout=12,api=30 if name=='hinge' else 29,expected='Fresh finite samples observed; variation is reported without assuming stationary sensors are broken',fail='Only invalid/non-finite values or non-monotonic timestamps can fail automatically',description='Move or rotate the phone, cover proximity/light, or walk for step sensors. Constant readings or no events are inconclusive, not hardware failure.')

camera_fields = {'facing':'Facing direction','hardware_level':'Camera2 hardware level','physical_ids':'Physical camera IDs','sizes':'JPEG output sizes','focal_lengths':'Focal lengths','apertures':'Apertures','focus_distance':'Minimum focus distance','af_modes':'Autofocus modes','flash':'Flash capability','max_zoom':'Maximum digital zoom','ois_modes':'OIS modes','video_stabilization':'Video stabilization modes','capabilities':'Camera2 capabilities','sensor_size':'Sensor pixel size','exposure_range':'Exposure compensation range','fps_ranges':'Target FPS ranges'}
for key,title in camera_fields.items():
    add('camera.{camera_id}.'+key,'Lens {camera_id}: '+title,'camera','camera_metadata',params={'field':key},capabilities=('camera',))
for key,title in [('preview','Preview initialization'),('capture','Photo capture'),('torch','Torch observation')]:
    add('camera.{camera_id}.'+key,'Lens {camera_id}: '+title,'camera','camera_capture' if key!='torch' else 'torch',mode='GUIDED_AUTOMATIC' if key!='torch' else 'USER_CONFIRMED',weight=3,capabilities=('camera','camera.{camera_id}.flash') if key=='torch' else ('camera',),permissions=('android.permission.CAMERA',),params={'test':key},timeout=90,expected='Real camera stream/capture succeeds; torch requires visible confirmation',fail='Camera operation returns a definitive error or user confirms failure',version=2 if key=='torch' else 1)

for key,title in [('inputs','Audio input devices'),('outputs','Audio output devices'),('volume','Media volume state')]:
    add('audio.'+key,title,'audio','audio_info',params={'field':key})
for key,title in [('capture','PCM sample capture'),('amplitude','Microphone activity'),('silence','Microphone silence indicator'),('clipping','Microphone clipping indicator')]:
    add('audio.mic.'+key,title,'audio','microphone',mode='GUIDED_AUTOMATIC',weight=3 if key=='capture' else 0,capabilities=('microphone',),permissions=('android.permission.RECORD_AUDIO',),params={'field':key},expected='AudioRecord captured samples; signal statistics are observational',fail='Recorder returned a definitive stream error',description='Speak at normal volume. Quiet/clipped sound may be environmental and is inconclusive. Raw samples are not saved or uploaded.')
for key,hz in [('low','200'),('mid','1000'),('high','4000')]:
    add('audio.speaker.'+key,key.title()+' speaker tone','audio','speaker',mode='USER_CONFIRMED',weight=2,params={'frequency':hz},expected='Inspector hears the generated tone without obvious distortion',fail='Inspector reports absent or distorted output',description='Start with comfortable media volume. Tone lasts two seconds; stop if uncomfortable.')
for key,title in [('vibration','Vibration response'),('volume_up','Volume-up button'),('volume_down','Volume-down button'),('power','Power-button guided observation'),('back','Back navigation')]:
    add('buttons.'+key,title,'buttons','buttons',mode='USER_CONFIRMED' if key in ('vibration','power') else 'GUIDED_AUTOMATIC',weight=1,params={'test':key},expected='Actual key event or explicit user confirmation observed',fail='User confirms required interaction failed',timeout=60)
add('biometric.authentication','Biometric authentication','biometrics','biometric',mode='GUIDED_AUTOMATIC',weight=2,expected='BiometricPrompt reports authentication success',fail='Authentication rejection is inconclusive; never infer sensor health from feature presence',timeout=60)

for key,title in {'wifi_enabled':'Wi-Fi enabled','transport':'Active network transports','validated':'OS-validated internet','wifi_link':'Wi-Fi link metadata','airplane':'Airplane mode','sim_state':'SIM state','nfc_enabled':'NFC enabled','usb_devices':'Connected USB devices','bluetooth_enabled':'Bluetooth enabled'}.items():
    add('network.'+key,title,'connectivity','network',params={'field':key},permissions=('android.permission.BLUETOOTH_CONNECT',) if key=='bluetooth_enabled' else ())
add('network.backend','Backend HTTPS reachability','connectivity','backend',weight=0,expected='Configured backend health endpoint responds',description='Network/service failures do not prove radio hardware failure')
add('network.ble_scan','Bluetooth LE discovery','connectivity','bluetooth_scan',mode='GUIDED_AUTOMATIC',capabilities=('ble',),permissions=('android.permission.BLUETOOTH_SCAN',),expected='Actual nearby advertisement observed; no devices yields inconclusive',timeout=12)
for key,title in [('fix','Fresh location fix'),('accuracy','Location accuracy'),('satellites','GNSS satellites')]:
    add('gnss.'+key,title,'connectivity','location',mode='GUIDED_AUTOMATIC',capabilities=('gps',),permissions=('android.permission.ACCESS_FINE_LOCATION',),params={'field':key},expected='Fresh location/satellite observation; report only accuracy/age/count, never coordinates',timeout=35)
for key,title in [('create','Private file creation'),('write','File write and flush'),('read','File read'),('checksum','SHA-256 round trip'),('overwrite','File overwrite'),('delete','Temporary file deletion')]:
    add('storage.'+key,title,'storage','storage',weight=2 if key=='checksum' else 0,critical=key=='checksum',params={'field':key},expected='Bounded app-private filesystem operation succeeds and content matches',fail='Actual I/O error or checksum mismatch',description='256 KiB app-private temporary file, removed in finally. This is not NAND/UFS health.')
add('storage.sqlite','SQLite transaction round trip','storage','sqlite',weight=1,expected='Inserted value is read back after transaction commits',fail='Database error or mismatched content')
add('performance.cpu','Short CPU performance snapshot','performance','cpu',expected='Bounded SHA-256 computation completed; duration reported without a health grade')
add('performance.memory','Safe allocation round trip','performance','memory',expected='1 MiB buffer allocated, written and verified; no lifespan claim')

disabled = {
 'camera':['autofocus_stability','focus_transition','ois_operation','white_balance_response','dark_frame','sharpness','noise','obstruction','frame_freeze','switch_stress','high_speed_video'],
 'touch':['ghost_touch','duplicate_anomalies','coordinate_alignment','hardware_sampling_rate'],
 'audio':['earpiece','speech','rattle','route_loopback','frequency_response','stream_continuity'],
 'battery':['health_percentage','oem_authenticity','current_stability'],
 'charging':['data_pin_health','accessory_communication','connection_instability'],
 'storage':['random_access','sequential_write','sequential_read','nand_health'],
 'security':['drm_level','drm_schemes','remote_attestation','motherboard_authenticity','repair_history','liquid_damage'],
 'connectivity':['nfc_tag','uwb_ranging','esim_profiles','bluetooth_pairing','dns_latency','wifi_reconnect'],
 'display':['auto_brightness','adaptive_refresh','objective_luminance'],
 'sensors':['stationary_drift','noise_calibration','significant_motion'],
}
for category, names in disabled.items():
    for name in names:
        add(category+'.'+name,name.replace('_',' ').title(),category,'unavailable',enabled=False,description='Disabled: requires a dedicated validated implementation, external equipment, official integration, or data unavailable to normal Android apps. Never reported as performed.')

assert len({d['id'] for d in definitions}) == len(definitions)
assert len(definitions) >= 250
(ROOT/'contracts/diagnostics.json').write_text(json.dumps(definitions,indent=2)+'\n',encoding='utf-8')
header = '# Diagnostic registry v1\n\nGenerated by tools/generate_registry.py. Camera templates expand only for exposed camera IDs. Metadata has zero scoring weight. ENABLED means a real implementation exists, subject to device validation. DISABLED checks are never fabricated as test results.\n\n'
columns=['id','category','title','mode','min_api','permissions','capabilities','implementation','pass_criteria','fail_criteria','not_supported','not_accessible','evidence_type']
table='| '+' | '.join(columns)+' |\n| '+' | '.join(['---']*len(columns))+' |\n'
for d in definitions:
    table += '| '+' | '.join(str(d[k]).replace('|','/') for k in columns)+' |\n'
(ROOT/'docs/diagnostic-registry.md').write_text(header+table,encoding='utf-8')
print(len(definitions),dict(Counter(d['implementation'] for d in definitions)))
print(dict(Counter(d['category'] for d in definitions if d['implementation']=='ENABLED')))
