import json
import subprocess
import os

locales = ['ja', 'en', 'de', 'es', 'ko', 'zh']
base_dir = os.path.dirname(os.path.abspath(__file__))

for locale in locales:
    path = os.path.join(base_dir, f'frontend/app/locales/{locale}/common.json')

    # Load HEAD version
    with open(path, encoding='utf-8') as f:
        head = json.load(f)

    # Get team_visibility from origin/main
    result = subprocess.run(
        ['git', 'show', f'origin/main:frontend/app/locales/{locale}/common.json'],
        capture_output=True, text=True, encoding='utf-8', cwd=base_dir
    )
    main_data = json.loads(result.stdout)

    # Merge: add team_visibility from main if not already present
    if 'team_visibility' in main_data and 'team_visibility' not in head:
        head['team_visibility'] = main_data['team_visibility']
        print(f'Added team_visibility to {locale}/common.json')
    else:
        print(f'{locale}/common.json: no change needed')

    # Write back
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(head, f, ensure_ascii=False, indent=2)
        f.write('\n')

print('Done')
