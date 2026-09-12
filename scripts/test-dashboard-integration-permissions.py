#!/usr/bin/env python3
"""使用临时角色和用户验证整合导航不会授予入站、密钥、重放或运维权限。"""
import sys
sys.dont_write_bytecode=True
import json,os,secrets,urllib.request,urllib.error,subprocess,pathlib
BASE=f"http://127.0.0.1:{os.environ.get('TEST_BACKEND_PORT','8080')}"
def request(method,path,body=None,token=''):
 headers={'Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 try:r=urllib.request.urlopen(req,timeout=15)
 except urllib.error.HTTPError as error:r=error
 return json.loads(r.read())
def require(response):
 assert response.get('code')==200,(response.get('code'),response.get('msg'))
 return response
_,suffix='role',secrets.token_hex(4)
name='access-test-'+suffix
admin=require(request('POST','/login',{'username':os.environ.get('TEST_ADMIN_USERNAME','admin'),'password':os.environ.get('TEST_ADMIN_PASSWORD','admin123'),'code':'','uuid':''}))['token']
role_id=user_id=None
try:
 require(request('POST','/system/role',{'roleName':name,'roleKey':name,'roleSort':99,'status':'0','dataScope':'1','menuCheckStrictly':True,'deptCheckStrictly':True,'menuIds':[2000,2002,2006],'deptIds':[]},admin))
 roles=require(request('GET','/system/role/list?roleName='+name,token=admin))['rows']
 role_id=next(row['roleId'] for row in roles if row['roleKey']==name)
 password='Test!'+secrets.token_hex(6)
 require(request('POST','/system/user',{'userName':name,'nickName':'权限隔离回归','password':password,'status':'0','sex':'0','deptId':103,'roleIds':[role_id],'postIds':[]},admin))
 users=require(request('GET','/system/user/list?userName='+name,token=admin))['rows']
 user_id=next(row['userId'] for row in users if row['userName']==name)
 token=require(request('POST','/login',{'username':name,'password':password,'code':'','uuid':''}))['token']
 info=require(request('GET','/getInfo',token=token))
 assert 'dashboard:access:list' in info['permissions'] and 'dashboard:dataset:list' in info['permissions']
 assert not any(permission.startswith('dashboard:integration:') for permission in info['permissions'])
 require(request('GET','/dashboard/source/list',token=token))
 for path in ['/dashboard/integration/list','/dashboard/integration/operations/metrics','/dashboard/integration/1/keys','/dashboard/integration/dead-letters','/dashboard/source/any-source/configuration','/dashboard/integration/endpoints/item/1/configuration','/dashboard/integration/key/1/configuration']:
  response=request('GET',path,token=token)
  assert response.get('code')==403,(path,response.get('code'))
 assert request('POST','/dashboard/integration/dead-letters/1/replay',{'reason':'must be denied'},token).get('code')==403
 print('普通数据集角色可使用整合导航及来源列表，入站、密钥、重放、运维接口均拒绝：通过')
finally:
 if user_id:require(request('DELETE',f'/system/user/{user_id}',token=admin))
 if role_id:require(request('DELETE',f'/system/role/{role_id}',token=admin))

 # RuoYi 删除用户/角色为逻辑删除；仅物理清理本轮且已被标记删除的测试记录。
 if user_id and role_id:
  env_file=pathlib.Path(__file__).resolve().parents[1]/'config/ruoyi-local.env'
  sql=f"delete from sys_user where user_id={int(user_id)} and user_name='{name}' and del_flag='2';delete from sys_role where role_id={int(role_id)} and role_key='{name}' and del_flag='2';"
  shell='set -a; source "$1"; set +a; MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h "$MYSQL_HOST" -P "${MYSQL_PORT:-3306}" -u "$MYSQL_USERNAME" "$MYSQL_DATABASE"'
  subprocess.run(['bash','-c',shell,'integration-permission-cleanup',str(env_file)],input=sql,text=True,check=True,stdout=subprocess.DEVNULL)
