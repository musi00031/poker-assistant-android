from itertools import combinations
from collections import Counter
import random

RANKS = "23456789TJQKA"
SUITS = "shdc"

def parse_card(s):
    s = s.strip()
    if len(s) != 2:
        raise ValueError(f"Неверная карта: {s}")
    r, suit = s[0].upper(), s[1].lower()
    if r not in RANKS or suit not in SUITS:
        raise ValueError(f"Неверная карта: {s}")
    return (RANKS.index(r) + 2, suit)

def card_name(c):
    return RANKS[c[0] - 2] + c[1]

def evaluate5(cards):
    vals = sorted((c[0] for c in cards), reverse=True)
    counts = Counter(vals)
    groups = sorted(counts.items(), key=lambda x: (x[1], x[0]), reverse=True)
    flush = len({c[1] for c in cards}) == 1
    unique = sorted(set(vals), reverse=True)

    straight_high = 0
    if len(unique) == 5:
        if unique[0] - unique[4] == 4:
            straight_high = unique[0]
        elif unique == [14, 5, 4, 3, 2]:
            straight_high = 5

    if flush and straight_high:
        return (8, straight_high)

    if groups[0][1] == 4:
        four = groups[0][0]
        kicker = max(v for v in vals if v != four)
        return (7, four, kicker)

    triples = sorted([v for v, n in counts.items() if n == 3], reverse=True)
    pairs = sorted([v for v, n in counts.items() if n == 2], reverse=True)

    if triples and (pairs or len(triples) > 1):
        pair = max(pairs + triples[1:])
        return (6, triples[0], pair)

    if flush:
        return (5, *vals)

    if straight_high:
        return (4, straight_high)

    if triples:
        kickers = sorted([v for v in vals if v != triples[0]], reverse=True)
        return (3, triples[0], *kickers)

    if len(pairs) >= 2:
        high_pair, low_pair = pairs[:2]
        kicker = max(v for v in vals if v not in (high_pair, low_pair))
        return (2, high_pair, low_pair, kicker)

    if pairs:
        pair = pairs[0]
        kickers = sorted([v for v in vals if v != pair], reverse=True)
        return (1, pair, *kickers)

    return (0, *vals)

def evaluate(cards):
    if len(cards) < 5:
        return None
    return max(evaluate5(list(combo)) for combo in combinations(cards, 5))

NAMES = [
    "Старшая карта", "Пара", "Две пары", "Сет/тройка",
    "Стрит", "Флеш", "Фулл-хаус", "Каре", "Стрит-флеш"
]

def equity(hero, board, simulations=10000):
    deck = [(r, s) for r in range(2, 15) for s in SUITS]
    known = hero + board
    for c in known:
        deck.remove(c)

    wins = ties = total = 0
    missing = 5 - len(board)

    for _ in range(simulations):
        draw = random.sample(deck, missing + 2)
        final_board = board + draw[:missing]
        villain = draw[missing:]
        h = evaluate(hero + final_board)
        v = evaluate(villain + final_board)

        if h > v:
            wins += 1
        elif h == v:
            ties += 1
        total += 1

    return (wins + ties / 2) / total * 100

def read_cards(prompt, allowed):
    while True:
        raw = input(prompt).strip()
        try:
            cards = [parse_card(x) for x in raw.split()]
            if len(cards) != allowed:
                print(f"Нужно указать ровно {allowed} карты.")
                continue
            if len(set(cards)) != len(cards):
                print("Повторяются карты.")
                continue
            return cards
        except ValueError as e:
            print(e)

def main():
    print("\n♠ POKER ASSISTANT — TEXAS HOLD'EM ♠")
    print("Карты: As Kh Qd Jc Ts")
    print("Масти: s=пики, h=червы, d=бубны, c=трефы\n")

    hero = read_cards("Твои две карты: ", 2)

    while True:
        try:
            n = int(input("Количество общих карт (0, 3, 4 или 5): "))
            if n in (0, 3, 4, 5):
                break
        except ValueError:
            pass
        print("Введи 0, 3, 4 или 5.")

    board = read_cards("Общие карты через пробел: ", n) if n else []

    if len(set(hero + board)) != len(hero + board):
        print("Ошибка: одна карта указана дважды.")
        return

    if len(board) >= 3:
        result = evaluate(hero + board)
        print("\nТекущая комбинация:", NAMES[result[0]])
    else:
        print("\nНа этой улице готовая комбинация ещё не определяется.")

    sims = 3000 if len(board) < 5 else 1
    if len(board) == 5:
        result = evaluate(hero + board)
        print("Эквити против случайной руки рассчитаем отдельно.")
    else:
        print(f"Рассчитываю эквити против случайной руки ({sims} симуляций)...")
        print(f"Эквити: {equity(hero, board, sims):.1f}%")

    print("\nПримечание: это оценка против случайной руки,")
    print("а не реального диапазона соперника.")

if __name__ == "__main__":
    main()
